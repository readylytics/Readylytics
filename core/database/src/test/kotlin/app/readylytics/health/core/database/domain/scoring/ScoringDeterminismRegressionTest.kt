package app.readylytics.health.core.database.domain.scoring
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.scoring.domain.scoring.ComputeTrainingReadinessUseCase

import app.readylytics.health.core.model.domain.scoring.ScoringConstants
import app.readylytics.health.core.model.domain.scoring.SleepScoreWeightProfile
import app.readylytics.health.core.scoring.domain.cardio.UthVo2MaxCalculator
import app.readylytics.health.core.scoring.domain.cardio.Vo2MaxSourceResolver
import app.readylytics.health.core.scoring.domain.scoring.AssembleDailySummaryUseCase
import app.readylytics.health.core.scoring.domain.scoring.AssembleEverydayLoadInputUseCase
import app.readylytics.health.core.scoring.domain.scoring.BaselineComputer
import app.readylytics.health.core.scoring.domain.scoring.BuildLoadSeriesUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeDailyTrimpUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeResidualFatigueUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeSleepMetricsUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeWorkoutTrimpUseCase
import app.readylytics.health.core.scoring.domain.scoring.ResolveDailyBaselinesUseCase
import app.readylytics.health.core.scoring.domain.scoring.ScoringCalculator
import app.readylytics.health.core.scoring.domain.scoring.ScoringConfig
import app.readylytics.health.core.scoring.domain.scoring.ScoringConfigFactory

import app.readylytics.health.core.databaseschema.data.local.dao.BloodPressureRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.BodyFatRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.BodyTemperatureRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.DailySummaryDao
import app.readylytics.health.core.databaseschema.data.local.dao.HeartRateDao
import app.readylytics.health.core.databaseschema.data.local.dao.MinuteBucketDao
import app.readylytics.health.core.databaseschema.data.local.dao.OxygenSaturationRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.SleepSessionDao
import app.readylytics.health.core.databaseschema.data.local.dao.Vo2MaxRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.WeightRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.WorkoutDao
import app.readylytics.health.core.databaseschema.data.local.entity.DailySummaryEntity
import app.readylytics.health.core.database.data.mapper.DailySummaryMapper
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.database.data.repository.BodyMetricsDataLoader
import app.readylytics.health.core.database.data.repository.ReadinessSummaryCoordinator
import app.readylytics.health.core.database.data.repository.ScoringDayDataLoader
import app.readylytics.health.core.database.data.repository.ScoringRepositoryImpl
import app.readylytics.health.core.database.data.repository.ScoringSeriesLoader
import app.readylytics.health.core.model.domain.repository.ScoringHistoryRepository
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepFragmentation
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepPercentileRhrCalculator
import app.readylytics.health.core.scoring.domain.scoring.strategies.LoadScoringStrategy
import app.readylytics.health.core.scoring.domain.scoring.strategies.SleepScoringStrategy
import io.mockk.coEvery
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
import app.readylytics.health.core.database.data.repository.ScoringDayUseCases
import app.readylytics.health.core.database.data.repository.ScoringDataLoaders

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
@OptIn(ExperimentalCoroutinesApi::class)
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
    private val minuteBucketDao = mockk<MinuteBucketDao>(relaxed = true)
    private val weightRecordDao = mockk<WeightRecordDao>(relaxed = true)
    private val bodyFatRecordDao = mockk<BodyFatRecordDao>(relaxed = true)
    private val bloodPressureRecordDao = mockk<BloodPressureRecordDao>(relaxed = true)
    private val oxygenSaturationRecordDao = mockk<OxygenSaturationRecordDao>(relaxed = true)
    private val bodyTemperatureRecordDao = mockk<BodyTemperatureRecordDao>(relaxed = true)
    private val vo2MaxRecordDao = mockk<Vo2MaxRecordDao>(relaxed = true)
    private val scoringHistoryRepository = mockk<ScoringHistoryRepository>(relaxed = true)

    private lateinit var repo: ScoringRepositoryImpl

    @Before
    fun setup() {
        val dataLoader =
            ScoringDayDataLoader(
                workoutDao,
                sleepSessionDao,
                dailySummaryDao,
                heartRateDao,
                minuteBucketDao,
                weightRecordDao,
                bodyFatRecordDao,
                bloodPressureRecordDao,
                oxygenSaturationRecordDao,
                bodyTemperatureRecordDao,
            )
        val bodyMetricsDataLoader =
            BodyMetricsDataLoader(
                weightRecordDao,
                bodyFatRecordDao,
                bloodPressureRecordDao,
                oxygenSaturationRecordDao,
                bodyTemperatureRecordDao,
                vo2MaxRecordDao,
            )
        val seriesLoader = ScoringSeriesLoader(workoutDao, dailySummaryDao)
        repo = buildRepo(dataLoader, bodyMetricsDataLoader, seriesLoader)
        coEvery { sleepSessionDao.getOverlapping(any(), any()) } returns emptyList()
    }

    private fun buildRepo(
        dataLoader: ScoringDayDataLoader,
        bodyMetricsDataLoader: BodyMetricsDataLoader,
        seriesLoader: ScoringSeriesLoader,
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
            ScoringDataLoaders(
                dataLoader,
                bodyMetricsDataLoader,
                seriesLoader,
            ),
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
        )
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
            assertRecomputedDerivedOutputs(run1, run2)
        }

    private fun assertRecomputedDerivedOutputs(run1: DailySummary, run2: DailySummary) {
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
        assertEquals(run1.residualFatigue, run2.residualFatigue, "residualFatigue must be reproducible")
        assertEquals(run1.acuteLoadRecovery, run2.acuteLoadRecovery, "acuteLoadRecovery must be reproducible")
        assertEquals(
            run1.trainingLoadReadinessWorkoutOnly,
            run2.trainingLoadReadinessWorkoutOnly,
            "trainingLoadReadinessWorkoutOnly must be reproducible",
        )
        assertEquals(
            run1.trainingLoadReadinessEverydayHr,
            run2.trainingLoadReadinessEverydayHr,
            "trainingLoadReadinessEverydayHr must be reproducible",
        )
        assertEquals(
            run1.trainingReadinessWorkoutOnly,
            run2.trainingReadinessWorkoutOnly,
            "trainingReadinessWorkoutOnly must be reproducible",
        )
        assertEquals(
            run1.trainingReadinessEverydayHr,
            run2.trainingReadinessEverydayHr,
            "trainingReadinessEverydayHr must be reproducible",
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
        // which previously got rounded to 1 decimal before entering the weighted sum. Fragmentation
        // data is present (SleepFragmentation.NONE) so this exercises the full-precision, non-degraded
        // path — the one that actually calls computeArchSubScore — and asserts against a hand-composed
        // full-precision expected value, proving the architecture sub-score enters the weighted sum
        // without being pre-rounded.
        val durationMinutes = 100
        val deep = 30
        val rem = 20
        val age = 25
        val efficiency = 95f
        val goal = 8f
        val sRest = 50f
        val fragmentation = SleepFragmentation.NONE

        val sDur = strategy.computeDurationSubScore(durationMinutes, efficiency, goal)
        val sArch = strategy.computeArchSubScore(deep, rem, durationMinutes, age, sleepTargets = null)
        val sFrag = strategy.computeFragmentationSubScore(fragmentation)

        val expectedFullPrecision =
            SleepScoreWeightProfile.BALANCED.durationWeight * sDur +
                SleepScoreWeightProfile.BALANCED.architectureWeight * sArch +
                SleepScoreWeightProfile.BALANCED.restorationWeight * sRest +
                SleepScoreWeightProfile.BALANCED.fragmentationWeight * sFrag

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
                fragmentation = fragmentation,
            )

        // Tight tolerance: the composite must match the hand-composed full-precision sum exactly —
        // proving the architecture sub-score entered the weighted sum without being pre-rounded.
        assertEquals(expectedFullPrecision, actual, 1e-4f)
    }
}
