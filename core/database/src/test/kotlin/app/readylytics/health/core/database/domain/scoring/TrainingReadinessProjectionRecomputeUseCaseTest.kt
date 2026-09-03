package app.readylytics.health.core.database.domain.scoring

import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.repository.ScoringHistoryRepository
import app.readylytics.health.core.model.domain.repository.TransactionRunner
import app.readylytics.health.core.model.domain.scoring.TrainingReadinessConfig
import app.readylytics.health.core.scoring.domain.scoring.ComputeTrainingReadinessUseCase
import app.readylytics.health.core.scoring.domain.scoring.ScoringCalculator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers the durable, parameter-only Training Readiness projection recompute (task 4): the
 * explicit-recompute button's mechanism, exercised directly against
 * [TrainingReadinessProjectionRecomputeUseCase] rather than through the worker. Reads/writes go
 * through the domain-safe [ScoringHistoryRepository] abstraction, never a raw DAO, matching
 * `CleanArchTest`'s "domain package does not import data package" rule.
 */
class TrainingReadinessProjectionRecomputeUseCaseTest {
    private val zoneId = ZoneId.of("UTC")
    private val scoringHistoryRepository = mockk<ScoringHistoryRepository>()
    private val scoringCalculator = mockk<ScoringCalculator>(relaxed = true)
    private val computeTrainingReadiness = ComputeTrainingReadinessUseCase(scoringCalculator)
    private var transactionRuns = 0
    private val trackingTransactionRunner =
        object : TransactionRunner {
            override suspend fun <R> runInTransaction(block: suspend () -> R): R {
                transactionRuns++
                return block()
            }
        }
    private val useCase =
        TrainingReadinessProjectionRecomputeUseCase(
            scoringHistoryRepository,
            trackingTransactionRunner,
            computeTrainingReadiness,
        )

    private fun summary(
        day: LocalDate,
        fatigue: Float?,
        load: Float?,
        legacyReadiness: Float?,
    ) = DailySummary(
        date = day,
        residualFatigue = fatigue,
        sRest = 80f,
        sleepScore = 75f,
        loadScoreWorkoutOnly = load,
        loadScoreEverydayHr = load,
        readinessWorkoutOnly = legacyReadiness,
        readinessEverydayHr = legacyReadiness,
    )

    private fun config(
        scale: Float = 40f,
        weight: Float = 0.7f,
    ) = TrainingReadinessConfig.fromStored(scale, weight)

    @Test
    fun `projection recompute uses one retained read and one transactional batch without raw-data calls`() =
        runTest {
            val rows =
                (1..3).map { day ->
                    summary(
                        LocalDate.of(2026, 1, day),
                        fatigue = day * 5f,
                        load = 60f,
                        legacyReadiness = 70f,
                    )
                }
            coEvery { scoringHistoryRepository.getDailySummariesSince(any(), any()) } returns rows
            val savedSlot = slot<List<DailySummary>>()
            coEvery { scoringHistoryRepository.upsertDailySummaries(capture(savedSlot), any()) } returns Unit

            val result = useCase.execute(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 3), zoneId, config())

            assertTrue(result.isSuccess)
            coVerify(exactly = 1) { scoringHistoryRepository.getDailySummariesSince(any(), any()) }
            coVerify(exactly = 1) { scoringHistoryRepository.upsertDailySummaries(any(), any()) }
            assertEquals(1, transactionRuns, "one transaction means one Room invalidation, not one per day")
            assertEquals(3, savedSlot.captured.size)
            savedSlot.captured.forEach { assertNotNull(it.trainingReadinessWorkoutOnly) }
            confirmVerified(scoringHistoryRepository)
        }

    @Test
    fun `projection recompute is idempotent for same rows and config`() =
        runTest {
            val rows = listOf(summary(LocalDate.of(2026, 1, 1), fatigue = 20f, load = 60f, legacyReadiness = 70f))
            coEvery { scoringHistoryRepository.getDailySummariesSince(any(), any()) } returns rows
            val firstSlot = slot<List<DailySummary>>()
            coEvery { scoringHistoryRepository.upsertDailySummaries(capture(firstSlot), any()) } returns Unit
            useCase.execute(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1), zoneId, config())

            val secondSlot = slot<List<DailySummary>>()
            coEvery { scoringHistoryRepository.upsertDailySummaries(capture(secondSlot), any()) } returns Unit
            useCase.execute(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1), zoneId, config())

            assertEquals(firstSlot.captured, secondSlot.captured)
        }

    @Test
    fun `cancelled projection does not commit partial rows`() =
        runTest {
            val rows = listOf(summary(LocalDate.of(2026, 1, 1), fatigue = 20f, load = 60f, legacyReadiness = 70f))
            coEvery { scoringHistoryRepository.getDailySummariesSince(any(), any()) } returns rows
            coEvery { scoringHistoryRepository.upsertDailySummaries(any(), any()) } throws
                CancellationException("cancelled")

            assertFailsWith<CancellationException> {
                useCase.execute(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1), zoneId, config())
            }
        }

    @Test
    fun `projection recompute reports progress once per retained row in order`() =
        runTest {
            val rows =
                (1..3).map { day ->
                    summary(LocalDate.of(2026, 1, day), fatigue = day * 5f, load = 50f, legacyReadiness = 60f)
                }
            coEvery { scoringHistoryRepository.getDailySummariesSince(any(), any()) } returns rows
            coEvery { scoringHistoryRepository.upsertDailySummaries(any(), any()) } returns Unit
            val progressUpdates = mutableListOf<Pair<Int, Int>>()

            useCase.execute(
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 3),
                zoneId,
                config(),
            ) { current, total -> progressUpdates += current to total }

            assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), progressUpdates)
        }

    @Test
    fun `an inverted range returns success without querying or writing`() =
        runTest {
            val result =
                useCase.execute(LocalDate.of(2026, 1, 10), LocalDate.of(2026, 1, 5), zoneId, config())

            assertTrue(result.isSuccess)
            coVerify(exactly = 0) { scoringHistoryRepository.getDailySummariesSince(any(), any()) }
            coVerify(exactly = 0) { scoringHistoryRepository.upsertDailySummaries(any(), any()) }
        }
}
