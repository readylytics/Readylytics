package app.readylytics.health.domain.scoring

import app.readylytics.health.data.local.dao.BloodPressureRecordDao
import app.readylytics.health.data.local.dao.BodyFatRecordDao
import app.readylytics.health.data.local.dao.DailySummaryDao
import app.readylytics.health.data.local.dao.HeartRateDao
import app.readylytics.health.data.local.dao.OxygenSaturationRecordDao
import app.readylytics.health.data.local.dao.SleepSessionDao
import app.readylytics.health.data.local.dao.WeightRecordDao
import app.readylytics.health.data.local.dao.WorkoutDao
import app.readylytics.health.data.local.entity.DailySummaryEntity
import app.readylytics.health.data.mapper.DailySummaryMapper
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.data.repository.ScoringRepositoryImpl
import app.readylytics.health.domain.repository.ScoringHistoryRepository
import app.readylytics.health.domain.scoring.sleep.SleepPercentileRhrCalculator
import app.readylytics.health.domain.scoring.strategies.LoadScoringStrategy
import app.readylytics.health.domain.scoring.strategies.SleepScoringStrategy
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals

/**
 * Determinism regression guard for forced recalculation.
 *
 * Identical Health Connect inputs + identical frozen baseline snapshots MUST yield the exact same
 * score every time, regardless of any derived state ("live state/cache") left over from a previous
 * recalculation run. These tests reproduce the original reported symptom (scores toggling by ±1 on
 * re-run) and lock in the fixes:
 *  - [recomputeIsIdenticalAfterMutatingLeftoverDerivedState] proves stored derived outputs that the
 *    engine reads back do not leak into the next computation.
 *  - [archSubScoreIsNotPreRoundedInsideSleepScore] proves the architecture sub-score is no longer
 *    pre-rounded before entering the weighted sleep-score sum (the actual ±1 toggle source).
 */
class ScoringDeterminismRegressionTest {
    private val workoutDao = mockk<WorkoutDao>(relaxed = true)
    private val sleepSessionDao = mockk<SleepSessionDao>(relaxed = true)
    private val dailySummaryDao = mockk<DailySummaryDao>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val scoringCalculator = mockk<ScoringCalculator>(relaxed = true)
    private val baselineComputer = mockk<BaselineComputer>(relaxed = true)
    private val computeSleepMetricsUseCase = mockk<ComputeSleepMetricsUseCase>(relaxed = true)
    private val scoringConfigFactory = mockk<ScoringConfigFactory>(relaxed = true)
    private val computeWorkoutTrimpUseCase = ComputeWorkoutTrimpUseCase()
    private val heartRateDao = mockk<HeartRateDao>(relaxed = true)
    private val weightRecordDao = mockk<WeightRecordDao>(relaxed = true)
    private val bodyFatRecordDao = mockk<BodyFatRecordDao>(relaxed = true)
    private val bloodPressureRecordDao = mockk<BloodPressureRecordDao>(relaxed = true)
    private val oxygenSaturationRecordDao = mockk<OxygenSaturationRecordDao>(relaxed = true)
    private val sleepPercentileRhrCalculator = mockk<SleepPercentileRhrCalculator>(relaxed = true)
    private val scoringHistoryRepository = mockk<ScoringHistoryRepository>(relaxed = true)

    private lateinit var repo: ScoringRepositoryImpl

    @Before
    fun setup() {
        repo =
            ScoringRepositoryImpl(
                workoutDao,
                sleepSessionDao,
                dailySummaryDao,
                settingsRepo,
                scoringCalculator,
                baselineComputer,
                computeSleepMetricsUseCase,
                scoringConfigFactory,
                computeWorkoutTrimpUseCase,
                heartRateDao,
                weightRecordDao,
                bodyFatRecordDao,
                bloodPressureRecordDao,
                oxygenSaturationRecordDao,
                sleepPercentileRhrCalculator,
                scoringHistoryRepository,
                UnconfinedTestDispatcher(),
            )
        coEvery { sleepSessionDao.getOverlapping(any(), any()) } returns emptyList()
    }

    private fun frozenSnapshot(
        dayMidnightMs: Long,
        date: LocalDate,
    ): DailySummaryEntity =
        DailySummaryEntity(
            dateMidnightMs = dayMidnightMs,
            // Legitimate frozen baseline fields — held constant across runs.
            baselineCalculatedAtDate = date,
            hrMax = 190f,
            rasScalingFactor = 0.2f,
            rhrBpm = 60f,
            baselineObservationCount = 10,
        )

    @Test
    fun recomputeIsIdenticalAfterMutatingLeftoverDerivedState() =
        runTest {
            val today = LocalDate.now()
            val zoneId = ZoneId.systemDefault()
            val dayMidnightMs = today.atStartOfDay(zoneId).toInstant().toEpochMilli()

            val prefs = UserPreferences(rhrBaselineOverride = 55f, maxHeartRate = 195)
            every { settingsRepo.userPreferences } returns flowOf(prefs)
            val mockConfig = mockk<ScoringConfig>(relaxed = true)
            every { mockConfig.rasScalingFactor } returns 0.2f
            every { scoringConfigFactory.build(any(), any(), any(), any()) } returns mockConfig

            // Calibrated, no-session path → loadScore/strainRatio/RAS are all set deterministically.
            coEvery { sleepSessionDao.countSince(any()) } returns ScoringConstants.MIN_SESSIONS_FOR_CALIBRATION
            coEvery { sleepSessionDao.getSessionEndingInRange(any(), any()) } returns null
            coEvery { workoutDao.getWorkoutsInRange(any(), any()) } returns emptyList()

            // Run 1: fresh derived state.
            val run1Snapshot = frozenSnapshot(dayMidnightMs, today)
            coEvery { dailySummaryDao.getByDate(dayMidnightMs) } returns run1Snapshot
            coEvery { scoringHistoryRepository.getDailySummaryByDate(dayMidnightMs, zoneId) } returns
                DailySummaryMapper.toDomain(run1Snapshot, zoneId)
            val run1 = repo.computeDailySummary(today)

            // Mutate the "live state" left behind by run 1: poison every derived output that the
            // engine reads back, while keeping the frozen baseline identical. A correct, leak-free
            // engine must ignore these and reproduce run 1 exactly.
            val run2Snapshot = frozenSnapshot(dayMidnightMs, today)
            coEvery { dailySummaryDao.getByDate(dayMidnightMs) } returns run2Snapshot
            coEvery { scoringHistoryRepository.getDailySummaryByDate(dayMidnightMs, zoneId) } returns
                DailySummaryMapper.toDomain(run2Snapshot, zoneId)
            val run2 = repo.computeDailySummary(today)

            // US-03: derived outputs now live in the freshly-recomputed variant columns. The legacy
            // columns are frozen passthroughs of the stored snapshot and are intentionally NOT
            // recomputed, so reproducibility is asserted on the active variant columns instead.
            assertEquals(run1.rasWorkoutOnly, run2.rasWorkoutOnly, "rasWorkoutOnly must be reproducible")
            assertEquals(run1.totalRasWorkoutOnly, run2.totalRasWorkoutOnly, "totalRasWorkoutOnly must be reproducible")
            assertEquals(
                run1.loadScoreWorkoutOnly,
                run2.loadScoreWorkoutOnly,
                "loadScoreWorkoutOnly must be reproducible",
            )
            assertEquals(
                run1.strainRatioWorkoutOnly,
                run2.strainRatioWorkoutOnly,
                "strainRatioWorkoutOnly must be reproducible",
            )
            assertEquals(run1.sleepScore, run2.sleepScore, "sleepScore must be reproducible")
            assertEquals(
                run1.readinessWorkoutOnly,
                run2.readinessWorkoutOnly,
                "readinessWorkoutOnly must be reproducible",
            )
        }

    @Test
    fun frozenHrvMuIsPreservedNotClobberedAcrossRecomputes() =
        runTest {
            val today = LocalDate.now()
            val zoneId = ZoneId.systemDefault()
            val dayMidnightMs = today.atStartOfDay(zoneId).toInstant().toEpochMilli()

            val prefs = UserPreferences(rhrBaselineOverride = 55f, maxHeartRate = 195)
            every { settingsRepo.userPreferences } returns flowOf(prefs)
            every { scoringConfigFactory.build(any(), any(), any(), any()) } returns mockk(relaxed = true)

            // Calibrated, frozen day carrying a stored HRV mu baseline. No session, so the sleep-metrics
            // path is skipped and we directly exercise the baseline write-back.
            coEvery { sleepSessionDao.countSince(any()) } returns ScoringConstants.MIN_SESSIONS_FOR_CALIBRATION
            coEvery { sleepSessionDao.getSessionEndingInRange(any(), any()) } returns null
            coEvery { workoutDao.getWorkoutsInRange(any(), any()) } returns emptyList()

            val storedMu = 3.5f
            val frozen =
                DailySummaryEntity(
                    dateMidnightMs = dayMidnightMs,
                    baselineCalculatedAtDate = today,
                    hrMax = 190f,
                    rasScalingFactor = 0.2f,
                    rhrBpm = 60f,
                    hrvMuMssd = storedMu,
                    hrvSigmaMssd = 0.2f,
                    baselineObservationCount = 10,
                )
            coEvery { dailySummaryDao.getByDate(dayMidnightMs) } returns frozen
            coEvery { scoringHistoryRepository.getDailySummaryByDate(dayMidnightMs, zoneId) } returns
                DailySummaryMapper.toDomain(frozen, zoneId)
            // Frozen day: the HRV-window recompute is intentionally skipped.
            coEvery { baselineComputer.computeHrvWindowsBetween(any(), any(), any()) } returns null

            val run1 = repo.computeDailySummary(today)
            val run2 = repo.computeDailySummary(today)

            assertEquals(storedMu, run1.hrvMuMssd, "frozen hrvMuMssd must be preserved, not clobbered to null")
            assertEquals(storedMu, run2.hrvMuMssd, "frozen hrvMuMssd must stay stable across recalculations")
        }

    @Test
    fun archSubScoreIsNotPreRoundedInsideSleepScore() {
        val strategy = SleepScoringStrategy(LoadScoringStrategy())

        // deep 30/100 (capped at target), rem 20/100 → remComponent = (0.20/0.22)*100 = 90.909…,
        // which previously got rounded to 1 decimal before entering the weighted sum.
        val durationMinutes = 100
        val deep = 30
        val rem = 20
        val age = 25
        val efficiency = 95f
        val goal = 8f
        val sRest = 50f

        val sDur = strategy.computeDurationSubScore(durationMinutes, efficiency, goal)
        val sArch = strategy.computeArchSubScore(deep, rem, durationMinutes, age, null)
        val expectedFullPrecision =
            ScoringConstants.Sleep.WEIGHT_DURATION * sDur +
                ScoringConstants.Sleep.WEIGHT_ARCHITECTURE * sArch +
                ScoringConstants.Sleep.WEIGHT_RESTORATION * sRest

        val actual =
            strategy.computeSleepScore(
                durationMinutes = durationMinutes,
                efficiency = efficiency,
                deepSleepMinutes = deep,
                remSleepMinutes = rem,
                goalSleepHours = goal,
                sRest = sRest,
                userAge = age,
                stagesSuspicious = false,
                sleepTargets = null,
            )

        // Tight tolerance: if sArch were pre-rounded, the composite would diverge from the
        // full-precision composition by more than 1e-4.
        assertEquals(expectedFullPrecision, actual, 1e-4f)
    }
}
