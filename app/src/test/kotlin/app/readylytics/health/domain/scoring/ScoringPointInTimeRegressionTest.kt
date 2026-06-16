package app.readylytics.health.domain.scoring

import app.readylytics.health.data.local.dao.*
import app.readylytics.health.data.local.entity.DailySummaryEntity
import app.readylytics.health.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.data.preferences.Gender
import app.readylytics.health.data.preferences.PhysiologyProfile
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.data.repository.ScoringRepositoryImpl
import app.readylytics.health.domain.scoring.sleep.SleepPercentileRhrCalculator
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ScoringPointInTimeRegressionTest {
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
    private val hrvDao = mockk<HrvDao>(relaxed = true)
    private val weightRecordDao = mockk<WeightRecordDao>(relaxed = true)
    private val bodyFatRecordDao = mockk<BodyFatRecordDao>(relaxed = true)
    private val bloodPressureRecordDao = mockk<BloodPressureRecordDao>(relaxed = true)
    private val oxygenSaturationRecordDao = mockk<OxygenSaturationRecordDao>(relaxed = true)
    private val sleepPercentileRhrCalculator = mockk<SleepPercentileRhrCalculator>(relaxed = true)

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
                hrvDao,
                weightRecordDao,
                bodyFatRecordDao,
                bloodPressureRecordDao,
                oxygenSaturationRecordDao,
                sleepPercentileRhrCalculator,
            )
    }

    @Test
    fun verifyHistoricalRecomputationIsStableAfterPreferencesChange() =
        runTest {
            val today = LocalDate.now()
            val zoneId = ZoneId.systemDefault()
            val dayMidnightMs = today.atStartOfDay(zoneId).toInstant().toEpochMilli()

            // 1. Setup a frozen snapshot daily summary for today
            val frozenSnapshot =
                DailySummaryEntity(
                    dateMidnightMs = dayMidnightMs,
                    baselineCalculatedAtDate = today,
                    hrMax = 190f,
                    rasScalingFactor = 0.2f,
                    rhrBpm = 60f,
                    baselineObservationCount = 10,
                )
            coEvery { dailySummaryDao.getByDate(dayMidnightMs) } returns frozenSnapshot

            // 2. Setup mock workouts/samples
            val workout =
                WorkoutRecordEntity(
                    id = "w1",
                    startTime = dayMidnightMs + 1000L,
                    endTime = dayMidnightMs + 3600000L,
                    exerciseType = "RUN",
                    durationMinutes = 60,
                    zone1Minutes = 0f,
                    zone2Minutes = 0f,
                    zone3Minutes = 0f,
                    zone4Minutes = 0f,
                    zone5Minutes = 0f,
                    trimp = 20f,
                    avgHr = 130f,
                )
            coEvery { workoutDao.getWorkoutsInRange(any(), any()) } returns listOf(workout)
            // triggers fallback using workout.trimp
            coEvery { heartRateDao.getByTimeRange(any(), any()) } returns emptyList()

            // 3. User Preferences setup: first run has ATHLETE profile
            val initialPrefs =
                UserPreferences(
                    physiologyProfile = PhysiologyProfile.ATHLETE,
                    maxHeartRate = 195,
                    rasScalingFactor = 0.25f,
                    rhrBaselineOverride = 55f,
                    gender = Gender.MALE,
                )
            coEvery { settingsRepo.userPreferences } returns flowOf(initialPrefs)
            val mockConfig = mockk<ScoringConfig>(relaxed = true)
            every { mockConfig.rasScalingFactor } returns 0.25f
            every { scoringConfigFactory.build(any(), any(), any(), any()) } returns mockConfig

            // Compute daily summary under initial preferences
            val result1 = repo.computeDailySummary(today)

            // 4. Mutate User Preferences: second run has SEDENTARY profile and different max HR
            val mutatedPrefs =
                UserPreferences(
                    physiologyProfile = PhysiologyProfile.SEDENTARY,
                    maxHeartRate = 170,
                    rasScalingFactor = 0.15f,
                    rhrBaselineOverride = 72f,
                    gender = Gender.FEMALE,
                )
            coEvery { settingsRepo.userPreferences } returns flowOf(mutatedPrefs)

            // Compute daily summary under mutated preferences
            val result2 = repo.computeDailySummary(today)

            // Assert that the computed metrics on this historical day are unchanged.
            // Reads target the new workout-only variant columns; legacy legacyRasScore/legacyTotalRas are no
            // longer written by computeDailySummary (frozen at migration time per US-03).
            assertEquals(
                result1.rasWorkoutOnly,
                result2.rasWorkoutOnly,
                "Workout-only RAS Score must remain unchanged",
            )
            assertEquals(
                result1.totalRasWorkoutOnly,
                result2.totalRasWorkoutOnly,
                "Workout-only Total RAS must remain unchanged",
            )

            // Legacy columns are no longer written by computeDailySummary.
            assertNull(result1.legacyRasScore, "Legacy legacyRasScore must not be written")
            assertNull(result1.legacyTotalRas, "Legacy legacyTotalRas must not be written")
        }

    @Test
    fun everydayAndWorkoutOnlyVariantsArePersistedIndependently() =
        runTest {
            val today = LocalDate.now()
            val zoneId = ZoneId.systemDefault()
            val dayMidnightMs = today.atStartOfDay(zoneId).toInstant().toEpochMilli()

            // Frozen snapshot so the calibration branch is skipped and ATL/CTL run.
            val frozenSnapshot =
                DailySummaryEntity(
                    dateMidnightMs = dayMidnightMs,
                    baselineCalculatedAtDate = today,
                    hrMax = 190f,
                    rasScalingFactor = 0.2f,
                    rhrBpm = 60f,
                    baselineObservationCount = 10,
                )
            coEvery { dailySummaryDao.getByDate(dayMidnightMs) } returns frozenSnapshot
            coEvery { sleepSessionDao.countSince(any()) } returns 10
            coEvery { sleepSessionDao.getSessionEndingInRange(any(), any()) } returns null

            val workout =
                WorkoutRecordEntity(
                    id = "w1",
                    startTime = dayMidnightMs + 1000L,
                    endTime = dayMidnightMs + 3600000L,
                    exerciseType = "RUN",
                    durationMinutes = 60,
                    zone1Minutes = 0f,
                    zone2Minutes = 0f,
                    zone3Minutes = 0f,
                    zone4Minutes = 0f,
                    zone5Minutes = 0f,
                    trimp = 20f,
                    avgHr = 130f,
                )
            coEvery { workoutDao.getWorkoutsInRange(any(), any()) } returns listOf(workout)
            coEvery { heartRateDao.getByTimeRange(any(), any()) } returns emptyList()

            val prefs =
                UserPreferences(
                    physiologyProfile = PhysiologyProfile.ATHLETE,
                    maxHeartRate = 195,
                    rasScalingFactor = 0.25f,
                    rhrBaselineOverride = 55f,
                    gender = Gender.MALE,
                )
            coEvery { settingsRepo.userPreferences } returns flowOf(prefs)
            val mockConfig = mockk<ScoringConfig>(relaxed = true)
            every { mockConfig.rasScalingFactor } returns 0.25f
            every { scoringConfigFactory.build(any(), any(), any(), any()) } returns mockConfig

            // Workout-only series for ATL/CTL; everyday series stays empty (no everyday HR present).
            coEvery { workoutDao.getDailyTrmpByEpochDay(any(), any(), any()) } returns
                mapOf(today.toEpochDay() to 20f)
            coEvery { dailySummaryDao.getEverydayTrimpByEpochDay(any(), any(), any()) } returns emptyMap()

            val result = repo.computeDailySummary(today)

            // Both variants persisted in distinct columns. With no everyday HR samples, the calculator
            // contributes zero non-workout TRIMP, so everyday TRIMP must equal workout-only TRIMP.
            assertNotNull(result.trimpWorkoutOnly, "Workout-only TRIMP persisted")
            assertEquals(
                result.trimpWorkoutOnly,
                result.trimpEverydayHr,
                "Everyday TRIMP equals workout-only when no everyday HR present",
            )
            assertEquals(LoadCoverageConfidence.NONE.name, result.everydayLoadConfidence)
            assertEquals(0, result.everydayCoverageMinutes)

            // Workout-only ATL/CTL columns are populated; everyday columns computed independently.
            assertNotNull(result.atlWorkoutOnly, "Workout-only ATL persisted")
            assertNotNull(result.ctlWorkoutOnly, "Workout-only CTL persisted")
            assertNotNull(result.atlEverydayHr, "Everyday ATL persisted")
            assertNotNull(result.ctlEverydayHr, "Everyday CTL persisted")

            // Legacy load columns are not written.
            assertNull(result.totalTrimp, "Legacy totalTrimp must not be written")
            assertNull(result.loadScore, "Legacy loadScore must not be written")
            assertNull(result.strainRatio, "Legacy strainRatio must not be written")
        }

    @Test
    fun everydayAtlCtlInjectionDoesNotContaminateWorkoutOnlySeries() =
        runTest {
            val today = LocalDate.now()
            val zoneId = ZoneId.systemDefault()
            val dayMidnightMs = today.atStartOfDay(zoneId).toInstant().toEpochMilli()

            val frozenSnapshot =
                DailySummaryEntity(
                    dateMidnightMs = dayMidnightMs,
                    baselineCalculatedAtDate = today,
                    hrMax = 190f,
                    rasScalingFactor = 0.2f,
                    rhrBpm = 60f,
                    baselineObservationCount = 10,
                )
            coEvery { dailySummaryDao.getByDate(dayMidnightMs) } returns frozenSnapshot
            coEvery { sleepSessionDao.countSince(any()) } returns 10
            coEvery { sleepSessionDao.getSessionEndingInRange(any(), any()) } returns null
            coEvery { workoutDao.getWorkoutsInRange(any(), any()) } returns emptyList()
            coEvery { heartRateDao.getByTimeRange(any(), any()) } returns emptyList()

            val prefs =
                UserPreferences(
                    physiologyProfile = PhysiologyProfile.ATHLETE,
                    maxHeartRate = 195,
                    rasScalingFactor = 0.25f,
                    rhrBaselineOverride = 55f,
                    gender = Gender.MALE,
                )
            coEvery { settingsRepo.userPreferences } returns flowOf(prefs)
            val mockConfig = mockk<ScoringConfig>(relaxed = true)
            every { mockConfig.rasScalingFactor } returns 0.25f
            every { scoringConfigFactory.build(any(), any(), any(), any()) } returns mockConfig

            // Capture the maps passed to ATL/CTL to prove no cross-contamination of the workout-only series.
            val atlMaps = mutableListOf<Map<LocalDate, Float>>()
            every { scoringCalculator.computeAtlEmaWithDecay(capture(atlMaps), any()) } returns 5f
            every { scoringCalculator.computeCtlEmaWithDecay(any(), any()) } returns 5f
            every { scoringCalculator.computeStrainRatio(any(), any()) } returns 1f
            every { scoringCalculator.computeLoadScore(any()) } returns 50f

            // Workout-only history has no entry for today; everyday history likewise empty. The everyday
            // path injects today's everyday TRIMP — the workout-only map must remain without it.
            coEvery { workoutDao.getDailyTrmpByEpochDay(any(), any(), any()) } returns
                mapOf(today.minusDays(1).toEpochDay() to 30f)
            coEvery { dailySummaryDao.getEverydayTrimpByEpochDay(any(), any(), any()) } returns emptyMap()

            repo.computeDailySummary(today)

            // First ATL call = workout-only series (no today key). Second = everyday series (today injected).
            assertEquals(2, atlMaps.size, "ATL computed once per variant")
            val workoutOnlyMap = atlMaps[0]
            val everydayMap = atlMaps[1]
            assertNull(workoutOnlyMap[today], "Workout-only series must NOT contain injected everyday value")
            assertEquals(0f, everydayMap[today], "Everyday series injects today's everyday TRIMP (0 with no HR)")
        }
}
