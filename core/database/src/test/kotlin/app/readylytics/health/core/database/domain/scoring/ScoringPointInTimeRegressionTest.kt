package app.readylytics.health.core.database.domain.scoring

import app.readylytics.health.core.model.domain.scoring.LoadCoverageConfidence
import app.readylytics.health.core.model.domain.scoring.ScoringConstants
import app.readylytics.health.core.scoring.domain.scoring.AssembleDailySummaryUseCase
import app.readylytics.health.core.scoring.domain.scoring.AssembleEverydayLoadInputUseCase
import app.readylytics.health.core.scoring.domain.scoring.BaselineComputer
import app.readylytics.health.core.scoring.domain.scoring.BuildLoadSeriesUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeDailyTrimpUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeSleepMetricsUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeWorkoutTrimpUseCase
import app.readylytics.health.core.scoring.domain.scoring.ResolveDailyBaselinesUseCase
import app.readylytics.health.core.scoring.domain.scoring.ScoringCalculator
import app.readylytics.health.core.scoring.domain.scoring.ScoringConfig
import app.readylytics.health.core.scoring.domain.scoring.ScoringConfigFactory

import app.readylytics.health.core.databaseschema.data.local.dao.*
import app.readylytics.health.core.databaseschema.data.local.entity.DailySummaryEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.core.database.data.mapper.DailySummaryMapper
import app.readylytics.health.core.model.data.preferences.Gender
import app.readylytics.health.core.model.data.preferences.PhysiologyProfile
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.database.data.repository.BodyMetricsDataLoader
import app.readylytics.health.core.database.data.repository.ReadinessSummaryCoordinator
import app.readylytics.health.core.database.data.repository.ScoringDayDataLoader
import app.readylytics.health.core.database.data.repository.ScoringRepositoryImpl
import app.readylytics.health.core.database.data.repository.ScoringSeriesLoader
import app.readylytics.health.core.model.domain.model.TimestampedTrimp
import app.readylytics.health.core.model.domain.repository.ScoringHistoryRepository
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepPercentileRhrCalculator
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
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
    private val minuteBucketDao = mockk<MinuteBucketDao>(relaxed = true)
    private val weightRecordDao = mockk<WeightRecordDao>(relaxed = true)
    private val bodyFatRecordDao = mockk<BodyFatRecordDao>(relaxed = true)
    private val bloodPressureRecordDao = mockk<BloodPressureRecordDao>(relaxed = true)
    private val oxygenSaturationRecordDao = mockk<OxygenSaturationRecordDao>(relaxed = true)
    private val bodyTemperatureRecordDao = mockk<BodyTemperatureRecordDao>(relaxed = true)
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
            )
        val seriesLoader = ScoringSeriesLoader(workoutDao, dailySummaryDao)
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
        repo =
            ScoringRepositoryImpl(
                dataLoader,
                bodyMetricsDataLoader,
                seriesLoader,
                settingsRepo,
                baselineComputer,
                scoringConfigFactory,
                ComputeDailyTrimpUseCase(computeWorkoutTrimpUseCase),
                ResolveDailyBaselinesUseCase(baselineComputer),
                AssembleEverydayLoadInputUseCase(),
                scoringHistoryRepository,
                readinessSummaryCoordinator,
                UnconfinedTestDispatcher(),
            )
    }

    private fun setupFrozenSnapshot(dayMidnightMs: Long, today: LocalDate, zoneId: ZoneId) {
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
        coEvery { scoringHistoryRepository.getDailySummaryByDate(dayMidnightMs, zoneId) } returns
            DailySummaryMapper.toDomain(frozenSnapshot, zoneId)
    }

    private fun setupWorkout(dayMidnightMs: Long): WorkoutRecordEntity {
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
        return workout
    }

    private fun setupPreferences(prefs: UserPreferences) {
        coEvery { settingsRepo.userPreferences } returns flowOf(prefs)
        val mockConfig = mockk<ScoringConfig>(relaxed = true)
        every { mockConfig.rasScalingFactor } returns prefs.rasScalingFactor
        every { scoringConfigFactory.build(any(), any(), any(), any()) } returns mockConfig
    }

    private fun athletePrefs() = UserPreferences(
        physiologyProfile = PhysiologyProfile.ATHLETE,
        maxHeartRate = 195,
        rasScalingFactor = 0.25f,
        rhrBaselineOverride = 55f,
        gender = Gender.MALE,
    )

    private fun sedentaryPrefs() = UserPreferences(
        physiologyProfile = PhysiologyProfile.SEDENTARY,
        maxHeartRate = 170,
        rasScalingFactor = 0.15f,
        rhrBaselineOverride = 72f,
        gender = Gender.FEMALE,
    )

    @Test
    fun verifyHistoricalRecomputationIsStableAfterPreferencesChange() =
        runTest {
            val today = LocalDate.now()
            val zoneId = ZoneId.systemDefault()
            val dayMidnightMs = today.atStartOfDay(zoneId).toInstant().toEpochMilli()

            setupFrozenSnapshot(dayMidnightMs, today, zoneId)
            setupWorkout(dayMidnightMs)
            setupPreferences(athletePrefs())

            val result1 = repo.computeDailySummary(today)

            setupPreferences(sedentaryPrefs())
            val result2 = repo.computeDailySummary(today)

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
        }

    @Test
    fun everydayAndWorkoutOnlyVariantsArePersistedIndependently() =
        runTest {
            val today = LocalDate.now()
            val zoneId = ZoneId.systemDefault()
            val dayMidnightMs = today.atStartOfDay(zoneId).toInstant().toEpochMilli()

            setupFrozenSnapshot(dayMidnightMs, today, zoneId)
            coEvery { sleepSessionDao.countSince(any()) } returns 10
            coEvery { sleepSessionDao.getSessionEndingInRange(any(), any()) } returns null

            val workout = setupWorkout(dayMidnightMs)
            setupPreferences(athletePrefs())

            coEvery { workoutDao.getTrimpPoints(any(), any()) } returns
                listOf(TimestampedTrimp(workout.startTime, 20f))
            coEvery { dailySummaryDao.getEverydayTrimpPoints(any(), any()) } returns emptyList()

            val result = repo.computeDailySummary(today)

            assertNotNull(result.trimpWorkoutOnly, "Workout-only TRIMP persisted")
            assertEquals(
                result.trimpWorkoutOnly,
                result.trimpEverydayHr,
                "Everyday TRIMP equals workout-only when no everyday HR present",
            )
            assertEquals(LoadCoverageConfidence.NONE.name, result.everydayLoadConfidence)
            assertEquals(0, result.everydayCoverageMinutes)

            assertNotNull(result.atlWorkoutOnly, "Workout-only ATL persisted")
            assertNotNull(result.ctlWorkoutOnly, "Workout-only CTL persisted")
            assertNotNull(result.atlEverydayHr, "Everyday ATL persisted")
            assertNotNull(result.ctlEverydayHr, "Everyday CTL persisted")
        }

    @Test
    fun everydayAtlCtlInjectionDoesNotContaminateWorkoutOnlySeries() =
        runTest {
            val today = LocalDate.now()
            val zoneId = ZoneId.systemDefault()
            val dayMidnightMs = today.atStartOfDay(zoneId).toInstant().toEpochMilli()

            setupFrozenSnapshot(dayMidnightMs, today, zoneId)
            coEvery { sleepSessionDao.countSince(any()) } returns 10
            coEvery { sleepSessionDao.getSessionEndingInRange(any(), any()) } returns null
            coEvery { workoutDao.getWorkoutsInRange(any(), any()) } returns emptyList()
            coEvery { heartRateDao.getByTimeRange(any(), any()) } returns emptyList()

            setupPreferences(athletePrefs())

            val atlMaps = mutableListOf<Map<LocalDate, Float>>()
            every { scoringCalculator.computeAtlEmaWithDecay(capture(atlMaps), any()) } returns 5f
            every { scoringCalculator.computeCtlEmaWithDecay(any(), any()) } returns 5f
            every { scoringCalculator.computeStrainRatio(any(), any()) } returns 1f
            every { scoringCalculator.computeLoadScore(any()) } returns 50f

            coEvery { workoutDao.getTrimpPoints(any(), any()) } returns
                listOf(TimestampedTrimp(today.minusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli(), 30f))
            coEvery { dailySummaryDao.getEverydayTrimpPoints(any(), any()) } returns emptyList()

            repo.computeDailySummary(today)

            assertEquals(2, atlMaps.size, "ATL computed once per variant")
            val workoutOnlyMap = atlMaps[0]
            val everydayMap = atlMaps[1]
            assertEquals(
                30f,
                workoutOnlyMap[today.minusDays(1)],
                "Workout-only historical entry preserved"
            )
            assertNull(
                everydayMap[today.minusDays(1)],
                "Workout-only historical entry must not leak into the everyday series"
            )
            assertEquals(
                0f,
                workoutOnlyMap[today],
                "Workout-only series now injects today's dailyTrimpRaw too (SCORE-005)"
            )
            assertEquals(
                0f,
                everydayMap[today],
                "Everyday series injects today's everyday TRIMP (0 with no HR)"
            )
        }

    @Test
    fun historicalAtlCtlIsDstSafeAndStableAcrossRepeatedCompute() =
        runTest {
            val originalTimeZone = TimeZone.getDefault()
            val zoneId = ZoneId.of("Europe/Berlin")
            TimeZone.setDefault(TimeZone.getTimeZone(zoneId))
            try {
                val targetDate = LocalDate.of(2025, 11, 3)
                val targetMidnightMs = targetDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
                val historicalDate = LocalDate.of(2025, 7, 1)
                val historicalMidnightMs = historicalDate.atStartOfDay(zoneId).toInstant().toEpochMilli()

                setupFrozenSnapshot(targetMidnightMs, targetDate, zoneId)
                coEvery { sleepSessionDao.countSince(any()) } returns 10
                coEvery { sleepSessionDao.getSessionEndingInRange(any(), any()) } returns null
                coEvery { workoutDao.getWorkoutsInRange(any(), any()) } returns emptyList()
                coEvery { heartRateDao.getByTimeRange(any(), any()) } returns emptyList()
                coEvery { workoutDao.getTrimpPoints(any(), any()) } returns
                    listOf(TimestampedTrimp(historicalMidnightMs, 30f))
                coEvery { dailySummaryDao.getEverydayTrimpPoints(any(), any()) } returns
                    listOf(TimestampedTrimp(historicalMidnightMs, 12f))

                setupPreferences(athletePrefs())

                val atlMaps = mutableListOf<Map<LocalDate, Float>>()
                val ctlMaps = mutableListOf<Map<LocalDate, Float>>()
                every { scoringCalculator.computeAtlEmaWithDecay(capture(atlMaps), any()) } returns 5f
                every { scoringCalculator.computeCtlEmaWithDecay(capture(ctlMaps), any()) } returns 5f
                every { scoringCalculator.computeStrainRatio(any(), any()) } returns 1f
                every { scoringCalculator.computeLoadScore(any()) } returns 50f

                val first = repo.computeDailySummary(targetDate)
                val second = repo.computeDailySummary(targetDate)

                assertEquals(30f, atlMaps[0][historicalDate])
                assertEquals(12f, atlMaps[1][historicalDate])
                assertEquals(atlMaps.take(2), atlMaps.drop(2))
                assertEquals(ctlMaps.take(2), ctlMaps.drop(2))
                assertEquals(first.atlWorkoutOnly, second.atlWorkoutOnly)
                assertEquals(first.readinessWorkoutOnly, second.readinessWorkoutOnly)
            } finally {
                TimeZone.setDefault(originalTimeZone)
            }
        }

    @Test
    fun ctlHistoryFetchStartsAtLocalMidnightAcrossDstTransitions() =
        runTest {
            val zoneId = ZoneId.of("Europe/Berlin")
            val prefs =
                UserPreferences(
                    scoringZoneId = zoneId.id,
                    physiologyProfile = PhysiologyProfile.ATHLETE,
                    maxHeartRate = 195,
                    rasScalingFactor = 0.25f,
                    rhrBaselineOverride = 55f,
                    gender = Gender.MALE,
                )
            val mockConfig = mockk<ScoringConfig>(relaxed = true)
            every { mockConfig.rasScalingFactor } returns 0.25f
            every { settingsRepo.userPreferences } returns flowOf(prefs)
            every { scoringConfigFactory.build(any(), any(), any(), any()) } returns mockConfig
            every { scoringCalculator.computeAtlEmaWithDecay(any(), any()) } returns 5f
            every { scoringCalculator.computeCtlEmaWithDecay(any(), any()) } returns 5f
            every { scoringCalculator.computeStrainRatio(any(), any()) } returns 1f
            every { scoringCalculator.computeLoadScore(any()) } returns 50f
            coEvery { sleepSessionDao.countSince(any()) } returns 10
            coEvery { sleepSessionDao.getSessionEndingInRange(any(), any()) } returns null
            coEvery { workoutDao.getWorkoutsInRange(any(), any()) } returns emptyList()
            coEvery { heartRateDao.getByTimeRange(any(), any()) } returns emptyList()
            coEvery { dailySummaryDao.getEverydayTrimpPoints(any(), any()) } returns emptyList()

            listOf(LocalDate.of(2025, 3, 31), LocalDate.of(2025, 10, 27)).forEach { targetDate ->
                val targetMidnightMs = targetDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
                val fromMs = slot<Long>()
                val frozenSnapshot =
                    DailySummaryEntity(
                        dateMidnightMs = targetMidnightMs,
                        baselineCalculatedAtDate = targetDate,
                        hrMax = 190f,
                        rasScalingFactor = 0.2f,
                        rhrBpm = 60f,
                        baselineObservationCount = 10,
                    )
                coEvery { dailySummaryDao.getByDate(targetMidnightMs) } returns frozenSnapshot
                coEvery { scoringHistoryRepository.getDailySummaryByDate(targetMidnightMs, zoneId) } returns
                    DailySummaryMapper.toDomain(frozenSnapshot, zoneId)
                coEvery { workoutDao.getTrimpPoints(capture(fromMs), any()) } returns emptyList()

                repo.computeDailySummary(targetDate)

                assertEquals(
                    targetDate
                        .minusDays(ScoringConstants.CHRONIC_DAYS * 2)
                        .atStartOfDay(zoneId)
                        .toInstant()
                        .toEpochMilli(),
                    fromMs.captured,
                )
            }
        }
}
