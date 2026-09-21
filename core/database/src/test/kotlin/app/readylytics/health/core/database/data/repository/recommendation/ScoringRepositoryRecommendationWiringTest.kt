package app.readylytics.health.core.database.data.repository.recommendation

import app.readylytics.health.core.database.data.mapper.WorkoutRecommendationCodec
import app.readylytics.health.core.database.data.repository.BodyMetricsDataLoader
import app.readylytics.health.core.database.data.repository.MorningRecommendationDependencies
import app.readylytics.health.core.database.data.repository.ReadinessSummaryCoordinator
import app.readylytics.health.core.database.data.repository.ScoringDataLoaders
import app.readylytics.health.core.database.data.repository.ScoringDayDataLoader
import app.readylytics.health.core.database.data.repository.ScoringDayUseCases
import app.readylytics.health.core.database.data.repository.ScoringHeartRateDataLoader
import app.readylytics.health.core.database.data.repository.ScoringRepositoryImpl
import app.readylytics.health.core.database.data.repository.ScoringSeriesLoader
import app.readylytics.health.core.databaseschema.data.local.dao.BloodPressureRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.BodyFatRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.BodyTemperatureRecordDao
import app.readylytics.health.core.databaseschema.data.local.entity.DailySummaryEntity
import app.readylytics.health.core.databaseschema.data.local.dao.DailySummaryDao
import app.readylytics.health.core.databaseschema.data.local.dao.HeartRateDao
import app.readylytics.health.core.databaseschema.data.local.dao.MinuteBucketDao
import app.readylytics.health.core.databaseschema.data.local.dao.OxygenSaturationRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.SleepSessionDao
import app.readylytics.health.core.databaseschema.data.local.dao.Vo2MaxRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.WeightRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.WorkoutDao
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.Result
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.recommendation.WorkoutRecommendationDecision
import app.readylytics.health.core.model.domain.recommendation.WorkoutRecommendationExample
import app.readylytics.health.core.model.domain.recommendation.WorkoutRecommendationReason
import app.readylytics.health.core.model.domain.recommendation.WorkoutRecommendationSnapshot
import app.readylytics.health.core.model.domain.recommendation.WorkoutRecommendationState
import app.readylytics.health.core.model.domain.repository.DailySummaryRepository
import app.readylytics.health.core.model.domain.repository.ScoringHistoryRepository
import app.readylytics.health.core.model.domain.repository.SleepSessionData
import app.readylytics.health.core.model.domain.repository.SleepSessionRepository
import app.readylytics.health.core.model.domain.repository.WorkoutRepository
import app.readylytics.health.core.model.domain.scoring.WorkoutLoadLevel
import app.readylytics.health.core.scoring.domain.cardio.UthVo2MaxCalculator
import app.readylytics.health.core.scoring.domain.cardio.Vo2MaxSourceResolver
import app.readylytics.health.core.scoring.domain.scoring.AssembleDailySummaryUseCase
import app.readylytics.health.core.scoring.domain.scoring.AssembleEverydayLoadInputUseCase
import app.readylytics.health.core.scoring.domain.scoring.BaselineComputer
import app.readylytics.health.core.scoring.domain.scoring.BuildLoadSeriesUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeDailyTrimpUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeResidualFatigueUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeSleepMetricsUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeTrainingReadinessUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeWorkoutTrimpUseCase
import app.readylytics.health.core.scoring.domain.scoring.GetWorkoutDisplayMetricsUseCase
import app.readylytics.health.core.scoring.domain.scoring.ResolveDailyBaselinesUseCase
import app.readylytics.health.core.scoring.domain.scoring.ScoringCalculator
import app.readylytics.health.core.scoring.domain.scoring.ScoringConfigFactory
import app.readylytics.health.core.scoring.domain.scoring.sleep.CurrentNightHrvResolver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.slot
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * Wiring coverage for Task 4: does [ScoringRepositoryImpl.computeDailySummary] actually call
 * [MorningRecommendationAssembler], persist the result atomically with the rest of the day, and
 * leave the prior row untouched when assembly fails? The evaluator/example-selection logic itself is
 * covered by [MorningRecommendationAssemblerTest]; this file only exercises the repository seam.
 *
 * Deliberately a standalone file rather than additions to `ScoringRepositoryImplTest` -- that class
 * already sits at detekt's `LargeClass` boundary, and growing it further to cover a feature its
 * existing tests don't otherwise touch would trip that threshold for no benefit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScoringRepositoryRecommendationWiringTest {
    private val workoutDao = mockk<WorkoutDao>(relaxed = true)
    private val sleepSessionDao = mockk<SleepSessionDao>(relaxed = true)
    private val dailySummaryDao = mockk<DailySummaryDao>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val scoringCalculator = mockk<ScoringCalculator>(relaxed = true)
    private val baselineComputer = mockk<BaselineComputer>(relaxed = true)
    private val computeSleepMetricsUseCase = mockk<ComputeSleepMetricsUseCase>(relaxed = true)
    private val scoringConfigFactory = mockk<ScoringConfigFactory>(relaxed = true)
    private val computeWorkoutTrimpUseCase = mockk<ComputeWorkoutTrimpUseCase>(relaxed = true)
    private val heartRateDao = mockk<HeartRateDao>(relaxed = true)
    private val minuteBucketDao = mockk<MinuteBucketDao>(relaxed = true)
    private val weightRecordDao = mockk<WeightRecordDao>(relaxed = true)
    private val bodyFatRecordDao = mockk<BodyFatRecordDao>(relaxed = true)
    private val bloodPressureRecordDao = mockk<BloodPressureRecordDao>(relaxed = true)
    private val oxygenSaturationRecordDao = mockk<OxygenSaturationRecordDao>(relaxed = true)
    private val bodyTemperatureRecordDao = mockk<BodyTemperatureRecordDao>(relaxed = true)
    private val vo2MaxRecordDao = mockk<Vo2MaxRecordDao>(relaxed = true)
    private val scoringHistoryRepository = mockk<ScoringHistoryRepository>(relaxed = true)

    private val recommendationSleepSessionRepository = mockk<SleepSessionRepository>(relaxed = true)
    private val recommendationComputeSleepMetricsUseCase = mockk<ComputeSleepMetricsUseCase>(relaxed = true)
    private val recommendationHrvResolver = mockk<CurrentNightHrvResolver>(relaxed = true)
    private val recommendationWorkoutRepository = mockk<WorkoutRepository>(relaxed = true)
    private val recommendationDailySummaryRepository = mockk<DailySummaryRepository>(relaxed = true)
    private val recommendationDisplayMetricsUseCase = mockk<GetWorkoutDisplayMetricsUseCase>(relaxed = true)

    private val dataLoader = ScoringDayDataLoader(workoutDao, sleepSessionDao, dailySummaryDao)
    private val bodyMetricsDataLoader =
        BodyMetricsDataLoader(
            weightRecordDao,
            bodyFatRecordDao,
            bloodPressureRecordDao,
            oxygenSaturationRecordDao,
            bodyTemperatureRecordDao,
            vo2MaxRecordDao,
        )
    private val seriesLoader = ScoringSeriesLoader(workoutDao, dailySummaryDao)
    private val heartRateDataLoader = ScoringHeartRateDataLoader(heartRateDao, minuteBucketDao)

    private fun createRepo(
        recommendationDependencies: MorningRecommendationDependencies = createRecommendationDependencies(),
    ): ScoringRepositoryImpl {
        val readinessSummaryCoordinator =
            ReadinessSummaryCoordinator(
                dataLoader,
                seriesLoader,
                scoringHistoryRepository,
                baselineComputer,
                BuildLoadSeriesUseCase(scoringCalculator),
                computeSleepMetricsUseCase,
                ResolveDailyBaselinesUseCase(baselineComputer),
                AssembleDailySummaryUseCase(),
            )
        return ScoringRepositoryImpl(
            ScoringDataLoaders(dataLoader, bodyMetricsDataLoader, seriesLoader, heartRateDataLoader),
            settingsRepo,
            baselineComputer,
            scoringConfigFactory,
            ScoringDayUseCases(
                ComputeDailyTrimpUseCase(computeWorkoutTrimpUseCase),
                ComputeResidualFatigueUseCase(),
                ResolveDailyBaselinesUseCase(baselineComputer),
                AssembleEverydayLoadInputUseCase(),
                ComputeTrainingReadinessUseCase(scoringCalculator),
                UthVo2MaxCalculator(),
                Vo2MaxSourceResolver(),
            ),
            scoringHistoryRepository,
            readinessSummaryCoordinator,
            UnconfinedTestDispatcher(),
            recommendationDependencies,
        )
    }

    private fun createRecommendationDependencies() =
        MorningRecommendationDependencies(
            sleepSessionRepository = recommendationSleepSessionRepository,
            computeSleepMetricsUseCase = recommendationComputeSleepMetricsUseCase,
            hrvResolver = recommendationHrvResolver,
            workoutRepository = recommendationWorkoutRepository,
            dailySummaryRepository = recommendationDailySummaryRepository,
            getWorkoutDisplayMetricsUseCase = recommendationDisplayMetricsUseCase,
        )

    @Before
    fun setup() {
        every { settingsRepo.userPreferences } returns flowOf(UserPreferences())
        coEvery { dailySummaryDao.getByDate(any()) } returns null
        coEvery { scoringHistoryRepository.getDailySummaryByDate(any(), any()) } returns null
        coEvery { sleepSessionDao.getOverlapping(any(), any()) } returns emptyList()
        coEvery { sleepSessionDao.countSince(any()) } returns 10
        coEvery {
            baselineComputer.computeAdaptiveBaselineRhrBpmBetween(any(), any(), any(), any(), any(), any())
        } returns 60f
        coEvery { baselineComputer.computeHrvWindowsBetween(any(), any(), any(), any(), any(), any()) } returns
            BaselineComputer.HrvWindows(
                muHistory = emptyList(),
                sigmaHistory = emptyList(),
                historicalSessions = emptyList(),
                validHistoricalSessionIds = emptyList(),
                validHistoricalDayCount = 6,
            )
    }

    @Test
    fun `computeDailySummary retains the previous available-state session over the habitual-wake selector`() =
        runTest {
            // Regression guard for the `previous` argument specifically: if `assemble(context,
            // previous = ...)` ever regresses to the one-arg `assemble(context)`, this session
            // retention is silently disabled and this test fails -- unlike a scenario with zero
            // candidates, where both the retention lookup and a dropped `previous` land on the same
            // NO_SLEEP result and a regression would go unnoticed.
            val today = LocalDate.now()
            val zoneId = ZoneId.systemDefault()
            val todayMs = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val habitualWakeHour = 7

            // Five strictly-earlier nights, all ending at 07:00, establish a habitual wake baseline
            // the selector would otherwise measure today's candidates against.
            val priorNights =
                (1..5).map { daysAgo ->
                    sleepData(
                        id = "prior-$daysAgo",
                        endMs = endOfDayMs(today.minusDays(daysAgo.toLong()), habitualWakeHour, zoneId),
                    )
                }
            // The habitual-wake selector's own pick: ends exactly at the 07:00 baseline, zero clock
            // distance -- it would win `selectByHabitualWake` outright if retention didn't short-circuit it.
            val habitualMatchSession =
                sleepData(id = "habitual-match", endMs = endOfDayMs(today, habitualWakeHour, zoneId))
            // The session the stale previous snapshot names: far from the habitual wake (03:00), so
            // it only wins if `previous` is actually honored.
            val retainedSession = sleepData(id = "retained-session", endMs = endOfDayMs(today, 3, zoneId))
            coEvery { recommendationSleepSessionRepository.getInRange(any(), any()) } returns
                (priorNights + listOf(habitualMatchSession, retainedSession))

            val staleSnapshot =
                WorkoutRecommendationSnapshot(
                    wakeSessionId = "retained-session",
                    wakeTimeMs = retainedSession.endTime,
                    decision =
                        WorkoutRecommendationDecision(
                            WorkoutRecommendationState.HARDER,
                            listOf(WorkoutRecommendationReason.WITHIN_USUAL_RANGE),
                        ),
                )
            coEvery { scoringHistoryRepository.getDailySummaryByDate(todayMs, zoneId) } returns
                DailySummary(date = today, workoutRecommendation = staleSnapshot)
            // A real session is resolved this time, so the recovery loader's sleep-metrics pass
            // actually runs (unlike the NO_SLEEP tests below, where it's never reached).
            coEvery { recommendationComputeSleepMetricsUseCase(any()) } returns
                Result.success(DailySummary(date = today))

            val repo = createRepo()

            val result = repo.computeDailySummary(today)

            // If `previous` were dropped, this would resolve to "habitual-match" instead.
            assertEquals("retained-session", result.workoutRecommendation?.wakeSessionId)
        }

    @Test
    fun `computeDailySummary populates workoutRecommendation via the wired assembler`() =
        runTest {
            val repo = createRepo(createRecommendationDependencies())
            coEvery { recommendationSleepSessionRepository.getInRange(any(), any()) } returns emptyList()

            val result = repo.computeDailySummary(LocalDate.now())

            assertEquals(WorkoutRecommendationState.NO_SLEEP, result.workoutRecommendation?.decision?.state)
            assertNull(result.workoutRecommendation?.wakeSessionId)
            assertNull(result.workoutRecommendation?.wakeTimeMs)
        }

    @Test
    fun `computeDailySummary replaces the prior snapshot wholesale rather than merging fields`() =
        runTest {
            val today = LocalDate.now()
            val zoneId = ZoneId.systemDefault()
            val todayMs = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val staleSnapshot =
                WorkoutRecommendationSnapshot(
                    wakeSessionId = "stale-session",
                    wakeTimeMs = todayMs + 25_200_000L,
                    decision =
                        WorkoutRecommendationDecision(
                            WorkoutRecommendationState.HARDER,
                            listOf(WorkoutRecommendationReason.WITHIN_USUAL_RANGE),
                        ),
                    examples =
                        listOf(
                            WorkoutRecommendationExample("w1", "Running", 0L, 1L, 30, 140f, WorkoutLoadLevel.HARD),
                            WorkoutRecommendationExample("w2", "Cycling", 0L, 1L, 45, 130f, WorkoutLoadLevel.MODERATE),
                        ),
                )
            val previousSummary = DailySummary(date = today, workoutRecommendation = staleSnapshot)
            coEvery { scoringHistoryRepository.getDailySummaryByDate(todayMs, zoneId) } returns previousSummary
            val repo = createRepo(createRecommendationDependencies())
            // No session ends today, so the fresh assembly resolves to NO_SLEEP with no examples --
            // a shape the assembler could never reach by merging fields from the stale snapshot.
            coEvery { recommendationSleepSessionRepository.getInRange(any(), any()) } returns emptyList()

            val result = repo.computeDailySummary(today)

            assertEquals(WorkoutRecommendationState.NO_SLEEP, result.workoutRecommendation?.decision?.state)
            assertEquals(emptyList(), result.workoutRecommendation?.decision?.reasons)
            assertEquals(emptyList(), result.workoutRecommendation?.examples)
        }

    @Test
    fun `computeDailySummary produces identical workoutRecommendation JSON for repeated identical inputs`() =
        runTest {
            val repo = createRepo(createRecommendationDependencies())
            coEvery { recommendationSleepSessionRepository.getInRange(any(), any()) } returns emptyList()

            val first = repo.computeDailySummary(LocalDate.now())
            val second = repo.computeDailySummary(LocalDate.now())

            val firstJson = first.workoutRecommendation?.let { WorkoutRecommendationCodec.encode(it) }
            val secondJson = second.workoutRecommendation?.let { WorkoutRecommendationCodec.encode(it) }
            assertEquals(firstJson, secondJson)
            assertNotEquals(null, firstJson)
        }

    @Test
    fun `computeAndPersistDailySummary does not upsert when recommendation assembly fails`() =
        runTest {
            val repo = createRepo(createRecommendationDependencies())
            coEvery { recommendationSleepSessionRepository.getInRange(any(), any()) } throws
                IllegalStateException("boom")

            assertFailsWith<IllegalStateException> {
                repo.computeAndPersistDailySummary(LocalDate.now())
            }

            coVerify(exactly = 0) { dailySummaryDao.upsert(any()) }
        }

    @Test
    fun `computeAndPersistDailySummary still persists the day when the sleep-metrics pass fails`() =
        runTest {
            // Regression guard for the scoring-version 4->5 backfill: this pass used to throw, which
            // aborted the whole day's scoring. One deterministically-failing historical day then
            // failed every retained-history recompute -- Result.retry(), no version bump, and the
            // startup gate re-enqueued the same doomed pass on every launch, forever.
            val today = LocalDate.now()
            val zoneId = ZoneId.systemDefault()
            val session = sleepData(id = "morning", endMs = endOfDayMs(today, 7, zoneId))
            coEvery { recommendationSleepSessionRepository.getInRange(any(), any()) } returns listOf(session)
            coEvery { recommendationComputeSleepMetricsUseCase(any()) } returns
                Result.failure("boom", "SLEEP_METRICS_ERROR")
            val repo = createRepo(createRecommendationDependencies())

            repo.computeAndPersistDailySummary(today)

            val persisted = slot<DailySummaryEntity>()
            coVerify(exactly = 1) { dailySummaryDao.upsert(capture(persisted)) }
            assertNull(persisted.captured.workoutRecommendationJson)
        }

    @Test
    fun `a failed sleep-metrics pass leaves an already-computed snapshot for the day intact`() =
        runTest {
            // The counterpart to the guard above: degrading to "no snapshot" must not *erase*
            // guidance a previous run already produced, or a transient failure would look like the
            // day was never computed and re-arm the restore-coverage backfill.
            val today = LocalDate.now()
            val zoneId = ZoneId.systemDefault()
            val todayMs = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val session = sleepData(id = "morning", endMs = endOfDayMs(today, 7, zoneId))
            val stored =
                WorkoutRecommendationSnapshot(
                    wakeSessionId = "morning",
                    wakeTimeMs = session.endTime,
                    decision =
                        WorkoutRecommendationDecision(
                            WorkoutRecommendationState.HARDER,
                            listOf(WorkoutRecommendationReason.WITHIN_USUAL_RANGE),
                        ),
                )
            coEvery { scoringHistoryRepository.getDailySummaryByDate(todayMs, zoneId) } returns
                DailySummary(date = today, workoutRecommendation = stored)
            coEvery { recommendationSleepSessionRepository.getInRange(any(), any()) } returns listOf(session)
            coEvery { recommendationComputeSleepMetricsUseCase(any()) } returns
                Result.failure("boom", "SLEEP_METRICS_ERROR")
            val repo = createRepo(createRecommendationDependencies())

            assertEquals(stored, repo.computeDailySummary(today).workoutRecommendation)
        }

    private fun endOfDayMs(
        date: LocalDate,
        hour: Int,
        zoneId: ZoneId,
    ): Long = date.atTime(hour, 0).atZone(zoneId).toInstant().toEpochMilli()

    private fun sleepData(
        id: String,
        endMs: Long,
        durationMinutes: Int = 480,
    ) = SleepSessionData(
        id = id,
        deviceName = "watch",
        startTime = endMs - durationMinutes * 60_000L,
        endTime = endMs,
        durationMinutes = durationMinutes,
        efficiency = 0.9f,
        deepSleepMinutes = 90,
        lightSleepMinutes = 280,
        remSleepMinutes = 90,
        awakeMinutes = 20,
    )
}
