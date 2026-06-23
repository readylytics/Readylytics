package app.readylytics.health.domain.sync

import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.HealthDataType
import app.readylytics.health.domain.preferences.SettingsRepository
import app.readylytics.health.domain.preferences.UserPreferences
import app.readylytics.health.domain.repository.HealthConnectRepository
import app.readylytics.health.domain.repository.ScoringRepository
import app.readylytics.health.domain.repository.TransactionRunner
import app.readylytics.health.domain.scoring.RasSourceModeBootstrapUseCase
import app.readylytics.health.domain.sync.HealthChangeSyncOutcome
import app.readylytics.health.domain.sync.HealthChangeSynchronizer
import app.readylytics.health.domain.sync.link.SessionLinkReconciler
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertFailsWith

class HealthSyncUseCaseTest {
    private val hcRepo = mockk<HealthConnectRepository>(relaxed = true)
    private val healthIngestionStore = mockk<HealthIngestionStore>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val scoringRepository = mockk<ScoringRepository>(relaxed = true)
    private val transactionRunner = mockk<TransactionRunner>(relaxed = true)
    private val sessionLinkReconciler = mockk<SessionLinkReconciler>(relaxed = true)
    private val rasSourceModeBootstrapUseCase = mockk<RasSourceModeBootstrapUseCase>(relaxed = true)
    private val changeSynchronizer = mockk<HealthChangeSynchronizer>(relaxed = true)
    private val selectedSourcePruner = mockk<SelectedSourcePruner>(relaxed = true)
    private val checkpointStore = mockk<ResyncCheckpointStore>(relaxed = true)

    private lateinit var useCase: HealthSyncUseCase

    @Before
    fun setup() {
        coEvery { transactionRunner.runInTransaction<Any>(any()) } coAnswers {
            val block = firstArg<suspend () -> Any>()
            block()
        }
        coEvery { changeSynchronizer.applyPendingChanges() } returns HealthChangeSyncOutcome(emptySet(), false)
        coJustRun { changeSynchronizer.commitTokens(any()) }
        every { checkpointStore.checkpoint } returns flowOf(null)

        useCase =
            HealthSyncUseCase(
                hcRepo = hcRepo,
                healthIngestionStore = healthIngestionStore,
                settingsRepo = settingsRepo,
                scoringRepository = scoringRepository,
                transactionRunner = transactionRunner,
                sessionLinkReconciler = sessionLinkReconciler,
                rasSourceModeBootstrapUseCase = rasSourceModeBootstrapUseCase,
                changeSynchronizer = changeSynchronizer,
                selectedSourcePruner = selectedSourcePruner,
                checkpointStore = checkpointStore,
                ioDispatcher = Dispatchers.Unconfined,
            )
        every { settingsRepo.userPreferences } returns flowOf(UserPreferences())
    }

    private fun summary(
        date: LocalDate = LocalDate.of(1970, 1, 1),
        stepCount: Int? = null,
    ): DailySummary = DailySummary(date = date, stepCount = stepCount)

    @Test
    fun `sync processes days in chronological order`() =
        runTest {
            val windowDays = 3
            val today = LocalDate.now(ZoneId.systemDefault())
            val day0 = today.minusDays(2)
            val day1 = today.minusDays(1)
            val day2 = today

            useCase.sync(windowDays = windowDays)

            coVerifyOrder {
                scoringRepository.computeAndPersistDailySummary(day0, 0L)
                scoringRepository.computeAndPersistDailySummary(day1, 0L)
                scoringRepository.computeAndPersistDailySummary(day2, 0L)
            }
        }

    @Test
    fun `sync commits candidate change tokens after scoring succeeds`() =
        runTest {
            val nextTokens = mapOf(HealthDataType.SLEEP to "next-sleep-token")
            coEvery { changeSynchronizer.applyPendingChanges() } returns
                HealthChangeSyncOutcome(
                    affectedDates = emptySet(),
                    requiresFullResync = false,
                    nextTokens = nextTokens,
                )

            useCase.sync(windowDays = 1)

            coVerifyOrder {
                scoringRepository.computeAndPersistDailySummary(any(), any())
                changeSynchronizer.commitTokens(nextTokens)
            }
        }

    @Test
    fun `sync clears frozen baselines for scoring window before recomputing days`() =
        runTest {
            val windowDays = 2
            val zoneId = ZoneId.systemDefault()
            val today = LocalDate.now(zoneId)
            val windowStartMs =
                today
                    .minusDays(1)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val windowEndExclusiveMs =
                today
                    .plusDays(1)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()

            useCase.sync(windowDays = windowDays)

            coVerifyOrder {
                healthIngestionStore.clearFrozenBaselines(today.minusDays(1), today.plusDays(1))
                scoringRepository.computeAndPersistDailySummary(today.minusDays(1), 0L)
                scoringRepository.computeAndPersistDailySummary(today, 0L)
            }
        }

    @Test
    fun `sync reconciles ingested overlap before scoring days`() =
        runTest {
            val windowDays = 1
            val zoneId = ZoneId.systemDefault()
            val today = LocalDate.now(zoneId)
            val ingestStartMs =
                today
                    .minusDays(1)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val windowEndExclusiveMs =
                today
                    .plusDays(1)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()

            coJustRun { sessionLinkReconciler.reconcile(any(), any(), any()) }

            useCase.sync(windowDays = windowDays)

            coVerifyOrder {
                sessionLinkReconciler.reconcile(
                    startMs = ingestStartMs,
                    endMs = windowEndExclusiveMs - 1,
                    zoneThresholds = any(),
                )
                healthIngestionStore.clearFrozenBaselines(today, today.plusDays(1))
                scoringRepository.computeAndPersistDailySummary(today, 0L)
            }
        }

    @Test
    fun `sync fetches and upserts all heart-related record types`() =
        runTest {
            // Mock non-empty returns to ensure mapping logic is triggered
            coEvery { hcRepo.readHeartRateSamples(any(), any()) } returns listOf(mockk(relaxed = true))
            coEvery { hcRepo.readHrvSamples(any(), any()) } returns listOf(mockk(relaxed = true))
            coEvery { hcRepo.readSteps(any(), any()) } returns 0L

            useCase.sync()

            coVerify {
                hcRepo.readHeartRateSamples(any(), any())
                hcRepo.readHrvSamples(any(), any())
                hcRepo.readSteps(any(), any())
                healthIngestionStore.persist(any())
            }
        }

    @Test
    fun `daily sync windowDays 1 fetches samples from yesterday to cover cross-midnight sleep`() =
        runTest {
            val hrvFromSlot = slot<Instant>()
            val hrFromSlot = slot<Instant>()
            coEvery { hcRepo.readHrvSamples(capture(hrvFromSlot), any()) } returns emptyList()
            coEvery { hcRepo.readHeartRateSamples(capture(hrFromSlot), any()) } returns emptyList()

            useCase.sync(windowDays = 1)

            // Last night's sleep session begins the previous evening (before midnight); the
            // ingestion fetch must reach back one extra day so its pre-midnight HR/HRV samples
            // are captured. windowDays = 1 => fetch from yesterday 00:00, not today 00:00.
            val zoneId = ZoneId.systemDefault()
            val yesterdayMidnight =
                LocalDate
                    .now(zoneId)
                    .minusDays(1)
                    .atStartOfDay(zoneId)
                    .toInstant()
            assertEquals(yesterdayMidnight, hrvFromSlot.captured)
            assertEquals(yesterdayMidnight, hrFromSlot.captured)
        }

    @Test
    fun `daily sync keeps current-day range and requests historical resync for older changes`() =
        runTest {
            val zoneId = ZoneId.systemDefault()
            val today = LocalDate.now(zoneId)
            // Beyond the inline-recompute floor: must escalate to the durable historical resync
            // rather than being absorbed by the foreground walk-forward.
            val oldestAffectedDay = today.minusDays(8)
            val hrFromSlot = slot<Instant>()
            val scoredDays = mutableListOf<LocalDate>()

            coEvery { changeSynchronizer.applyPendingChanges() } returns
                HealthChangeSyncOutcome(
                    affectedDates = setOf(oldestAffectedDay),
                    requiresFullResync = false,
                    nextTokens = mapOf(HealthDataType.SLEEP to "next-sleep-token"),
                )
            coEvery { hcRepo.readHeartRateSamples(capture(hrFromSlot), any()) } returns emptyList()
            coJustRun { scoringRepository.computeAndPersistDailySummary(capture(scoredDays), any()) }

            val result = useCase.sync(windowDays = 1)

            assertEquals(today.minusDays(1).atStartOfDay(zoneId).toInstant(), hrFromSlot.captured)
            assertEquals(listOf(today), scoredDays)
            assertTrue(result is app.readylytics.health.domain.model.Result.Failure)
            assertEquals(
                "REQUIRES_HISTORICAL_RESYNC",
                (result as app.readylytics.health.domain.model.Result.Failure).code,
            )
            coVerify(exactly = 0) { changeSynchronizer.commitTokens(any()) }
        }

    @Test
    fun `daily sync absorbs recent out-of-window change inline without historical resync`() =
        runTest {
            val zoneId = ZoneId.systemDefault()
            val today = LocalDate.now(zoneId)
            val yesterday = today.minusDays(1)
            val nextTokens = mapOf(HealthDataType.SLEEP to "next-sleep-token")
            val hrFromSlot = slot<Instant>()
            val scoredDays = mutableListOf<LocalDate>()

            coEvery { changeSynchronizer.applyPendingChanges() } returns
                HealthChangeSyncOutcome(
                    affectedDates = setOf(yesterday),
                    requiresFullResync = false,
                    nextTokens = nextTokens,
                )
            coEvery { hcRepo.readHeartRateSamples(capture(hrFromSlot), any()) } returns emptyList()
            coJustRun { scoringRepository.computeAndPersistDailySummary(capture(scoredDays), any()) }

            val result = useCase.sync(windowDays = 1)

            // Walk-forward widens to the affected day and recomputes it through today, contiguously.
            assertEquals(listOf(yesterday, today), scoredDays)
            // Ingestion reaches one extra day back from the widened oldest target day.
            assertEquals(today.minusDays(2).atStartOfDay(zoneId).toInstant(), hrFromSlot.captured)
            assertTrue(result is app.readylytics.health.domain.model.Result.Success)
            coVerify(exactly = 1) { changeSynchronizer.commitTokens(nextTokens) }
        }

    @Test
    fun `daily sync absorbs change exactly at the inline floor inline`() =
        runTest {
            val zoneId = ZoneId.systemDefault()
            val today = LocalDate.now(zoneId)
            // Exactly MAX_INLINE_RECOMPUTE_DAYS (7) back: the floor is inclusive, so still inline.
            val floorDay = today.minusDays(7)
            val nextTokens = mapOf(HealthDataType.SLEEP to "next-sleep-token")
            val scoredDays = mutableListOf<LocalDate>()

            coEvery { changeSynchronizer.applyPendingChanges() } returns
                HealthChangeSyncOutcome(
                    affectedDates = setOf(floorDay),
                    requiresFullResync = false,
                    nextTokens = nextTokens,
                )
            coJustRun { scoringRepository.computeAndPersistDailySummary(capture(scoredDays), any()) }

            val result = useCase.sync(windowDays = 1)

            assertEquals(floorDay, scoredDays.first())
            assertEquals(today, scoredDays.last())
            assertTrue(result is app.readylytics.health.domain.model.Result.Success)
            coVerify(exactly = 1) { changeSynchronizer.commitTokens(nextTokens) }
        }

    @Test
    fun `resyncRange first chunk fetches samples from startDate minus 1 to cover cross-midnight sleep`() =
        runTest {
            val zoneId = ZoneId.systemDefault()
            val startDate = LocalDate.of(2024, 6, 1)
            val endDate = LocalDate.of(2024, 6, 2)

            val sleepFromSlot = slot<Instant>()
            val hrvFromSlot = slot<Instant>()
            val hrFromSlot = slot<Instant>()
            coEvery { hcRepo.readSleepSessions(capture(sleepFromSlot), any()) } returns emptyList()
            coEvery { hcRepo.readHrvSamples(capture(hrvFromSlot), any()) } returns emptyList()
            coEvery { hcRepo.readHeartRateSamples(capture(hrFromSlot), any()) } returns emptyList()
            useCase.resyncRange(startDate, endDate)

            // The first chunk of resyncRange must reach back one extra day to capture
            // overnight sleep sessions that began the previous evening.
            val reachBackMidnight =
                startDate
                    .minusDays(1)
                    .atStartOfDay(zoneId)
                    .toInstant()
            assertEquals(reachBackMidnight, sleepFromSlot.captured)
            assertEquals(reachBackMidnight, hrvFromSlot.captured)
            assertEquals(reachBackMidnight, hrFromSlot.captured)
        }

    @Test
    fun `resyncRange each chunk fetches samples from previous day to cover cross-midnight sleep`() =
        runTest {
            val zoneId = ZoneId.systemDefault()
            val startDate = LocalDate.of(2024, 6, 1)
            val endDate = LocalDate.of(2024, 7, 2)
            val chunkDays = 30

            val hrvFromInstants = mutableListOf<Instant>()
            val hrFromInstants = mutableListOf<Instant>()
            coEvery { hcRepo.readHrvSamples(capture(hrvFromInstants), any()) } returns emptyList()
            coEvery { hcRepo.readHeartRateSamples(capture(hrFromInstants), any()) } returns emptyList()
            useCase.resyncRange(startDate, endDate, chunkDays = chunkDays)

            val secondChunkStart = startDate.plusDays(chunkDays.toLong())
            val expectedSecondChunkReadStart =
                secondChunkStart
                    .minusDays(1)
                    .atStartOfDay(zoneId)
                    .toInstant()
            assertEquals(expectedSecondChunkReadStart, hrvFromInstants[1])
            assertEquals(expectedSecondChunkReadStart, hrFromInstants[1])
        }

    @Test
    fun `resyncRange caps sleep and workout interval reads to chunk end`() =
        runTest {
            val zoneId = ZoneId.systemDefault()
            val startDate = LocalDate.of(2024, 6, 1)
            val chunkDays = 30
            val firstChunkEnd =
                startDate
                    .plusDays(chunkDays.toLong())
                    .atStartOfDay(zoneId)
                    .toInstant()

            val sleepToInstants = mutableListOf<Instant>()
            val workoutToInstants = mutableListOf<Instant>()
            val hrvToInstants = mutableListOf<Instant>()
            val hrToInstants = mutableListOf<Instant>()
            coEvery { hcRepo.readSleepSessions(any(), capture(sleepToInstants)) } returns emptyList()
            coEvery { hcRepo.readExerciseSessions(any(), capture(workoutToInstants)) } returns emptyList()
            coEvery { hcRepo.readHrvSamples(any(), capture(hrvToInstants)) } returns emptyList()
            coEvery { hcRepo.readHeartRateSamples(any(), capture(hrToInstants)) } returns emptyList()
            useCase.resyncRange(
                startDate = startDate,
                endDate = LocalDate.of(2024, 7, 2),
                chunkDays = chunkDays,
            )

            assertEquals(firstChunkEnd, sleepToInstants[0])
            assertEquals(firstChunkEnd, workoutToInstants[0])
            assertEquals(firstChunkEnd, hrvToInstants[0])
            assertEquals(firstChunkEnd, hrToInstants[0])
        }

    @Test
    fun `resyncRange filters selected step device using raw step records`() =
        runTest {
            every { settingsRepo.userPreferences } returns
                flowOf(
                    UserPreferences(
                        deviceByDataType = mapOf(HealthDataType.STEPS.name to "Watch"),
                    ),
                )
            useCase.resyncRange(
                startDate = LocalDate.of(2024, 6, 1),
                endDate = LocalDate.of(2024, 6, 1),
            )

            coVerify(exactly = 1) { hcRepo.readStepsRecords(any(), any()) }
            coVerify(exactly = 0) { hcRepo.readSteps(any(), any()) }
        }

    @Test
    fun `resyncRange retries selected step device raw record fetch`() =
        runTest {
            every { settingsRepo.userPreferences } returns
                flowOf(
                    UserPreferences(
                        deviceByDataType = mapOf(HealthDataType.STEPS.name to "Watch"),
                    ),
                )
            coEvery { hcRepo.readStepsRecords(any(), any()) } throws RuntimeException("rate limited") andThen
                emptyList()

            useCase.resyncRange(
                startDate = LocalDate.of(2024, 6, 1),
                endDate = LocalDate.of(2024, 6, 1),
            )

            coVerify(exactly = 2) { hcRepo.readStepsRecords(any(), any()) }
        }

    @Test
    fun `resyncRange writes zero steps for selected device days without raw step records`() =
        runTest {
            every { settingsRepo.userPreferences } returns
                flowOf(
                    UserPreferences(
                        deviceByDataType = mapOf(HealthDataType.STEPS.name to "Watch"),
                    ),
                )
            coEvery { hcRepo.readStepsRecords(any(), any()) } returns emptyList()
            val date = LocalDate.of(2024, 6, 1)

            useCase.resyncRange(
                startDate = date,
                endDate = date,
            )

            coVerify { scoringRepository.computeAndPersistDailySummary(date, 0L) }
        }

    @Test
    fun `resyncRange progress reports calendar days not internal two phase steps`() =
        runTest {
            val progress = mutableListOf<Pair<Int, Int>>()

            useCase.resyncRange(
                startDate = LocalDate.of(2024, 6, 1),
                endDate = LocalDate.of(2024, 6, 3),
            ) { current, total ->
                progress += current to total
            }

            assertEquals(3, progress.last().first)
            assertEquals(3, progress.last().second)
            assert(progress.all { (_, total) -> total == 3 })
        }

    @Test
    fun `resyncRange clears frozen baselines only for requested range before walk-forward recompute`() =
        runTest {
            val zoneId = ZoneId.systemDefault()
            val startDate = LocalDate.of(2024, 6, 1)
            val endDate = LocalDate.of(2024, 6, 3)
            val clearFromMs = startDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val clearToExclusiveMs =
                endDate
                    .plusDays(1)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()

            useCase.resyncRange(startDate = startDate, endDate = endDate)

            coVerifyOrder {
                healthIngestionStore.clearFrozenBaselines(startDate, endDate.plusDays(1))
                scoringRepository.computeAndPersistDailySummary(startDate, any())
                scoringRepository.computeAndPersistDailySummary(startDate.plusDays(1), any())
                scoringRepository.computeAndPersistDailySummary(endDate, any())
            }
        }

    @Test
    fun `sync rethrows cancellation instead of converting to failure`() =
        runTest {
            coEvery { hcRepo.readSleepSessions(any(), any()) } throws CancellationException("cancelled")

            assertFailsWith<CancellationException> {
                useCase.sync(windowDays = 1)
            }
        }

    @Test
    fun `resyncRange calls SelectedSourcePruner after ingestion and before session linkage reconciliation`() =
        runTest {
            val startDate = LocalDate.of(2024, 6, 1)
            val endDate = LocalDate.of(2024, 6, 3)

            useCase.resyncRange(startDate = startDate, endDate = endDate)

            coVerifyOrder {
                selectedSourcePruner.prune(startDate, endDate, any(), any())
                sessionLinkReconciler.reconcile(any(), any(), any())
                scoringRepository.computeAndPersistDailySummary(startDate, any())
            }
        }
}
