package app.readylytics.health.domain.scoring

import app.readylytics.health.data.local.dao.BloodPressureRecordDao
import app.readylytics.health.data.local.dao.BodyFatRecordDao
import app.readylytics.health.data.local.dao.DailySummaryDao
import app.readylytics.health.data.local.dao.HeartRateDao
import app.readylytics.health.data.local.dao.HrvDao
import app.readylytics.health.data.local.dao.OxygenSaturationRecordDao
import app.readylytics.health.data.local.dao.SleepHrSample
import app.readylytics.health.data.local.dao.SleepSessionDao
import app.readylytics.health.data.local.dao.WeightRecordDao
import app.readylytics.health.data.local.dao.WorkoutDao
import app.readylytics.health.data.local.entity.DailySummaryEntity
import app.readylytics.health.data.local.entity.SleepSessionEntity
import app.readylytics.health.data.preferences.Gender
import app.readylytics.health.data.preferences.PhysiologyProfile
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.data.repository.ScoringHistoryRepositoryImpl
import app.readylytics.health.data.repository.ScoringRepositoryImpl
import app.readylytics.health.data.security.EncryptionManager
import app.readylytics.health.domain.repository.ScoringRepository
import app.readylytics.health.domain.scoring.sleep.CurrentNightHrvResolver
import app.readylytics.health.domain.scoring.sleep.HrCoverageValidator
import app.readylytics.health.domain.scoring.sleep.SleepNadirAnalyzer
import app.readylytics.health.domain.scoring.sleep.SleepPercentileRhrCalculator
import app.readylytics.health.domain.scoring.strategies.LoadScoringStrategy
import app.readylytics.health.domain.scoring.strategies.RasScoringStrategy
import app.readylytics.health.domain.scoring.strategies.SleepScoringStrategy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class ScoringRepositoryN1Test {
    private lateinit var heartRateDao: HeartRateDao
    private lateinit var sleepSessionDao: SleepSessionDao
    private lateinit var hrvDao: HrvDao
    private lateinit var workoutDao: WorkoutDao
    private lateinit var dailySummaryDao: DailySummaryDao
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var scoringCalculator: ScoringCalculator
    private lateinit var weightRecordDao: WeightRecordDao
    private lateinit var bodyFatRecordDao: BodyFatRecordDao
    private lateinit var bloodPressureRecordDao: BloodPressureRecordDao
    private lateinit var repo: ScoringRepository

    private val todayMidnight =
        LocalDate
            .now()
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private fun makeSleepSession(
        id: String,
        offsetDays: Int,
    ) = SleepSessionEntity(
        id = id,
        startTime = todayMidnight - offsetDays.toLong() * 86_400_000L - 8 * 3_600_000L,
        endTime = todayMidnight - offsetDays.toLong() * 86_400_000L + 1_800_000L,
        durationMinutes = 450,
        efficiency = 85f,
        deepSleepMinutes = 90,
        remSleepMinutes = 90,
        lightSleepMinutes = 210,
        awakeMinutes = 15,
        deviceName = "TestDevice",
    )

    @Before
    fun setUp() {
        heartRateDao = mockk()
        sleepSessionDao = mockk()
        hrvDao = mockk()
        workoutDao = mockk()
        dailySummaryDao = mockk()
        settingsRepo = mockk()
        weightRecordDao = mockk()
        bodyFatRecordDao = mockk()
        bloodPressureRecordDao = mockk()

        coEvery { weightRecordDao.getLatestUpTo(any()) } returns null
        coEvery { bodyFatRecordDao.getLatestUpTo(any()) } returns null
        coEvery { bloodPressureRecordDao.getLatestUpTo(any()) } returns null

        every { settingsRepo.userPreferences } returns
            MutableStateFlow(UserPreferences(physiologyProfile = PhysiologyProfile.ACTIVE))

        coEvery { sleepSessionDao.countSince(any()) } returns 10
        val historicSessions = (1..9).map { makeSleepSession("historic_$it", it) }
        val todaySession = makeSleepSession("today", 0)
        coEvery { sleepSessionDao.getSessionEndingInRange(any(), any()) } returns todaySession
        coEvery { sleepSessionDao.getSince(any()) } returns historicSessions + todaySession
        coEvery { sleepSessionDao.getBetween(any(), any()) } returns historicSessions + todaySession

        coEvery { workoutDao.getTrimpPoints(any(), any()) } returns emptyList()
        coEvery { workoutDao.getTotalTrimp(any(), any()) } returns 0f
        coEvery { workoutDao.getTotalDurationMinutes(any(), any()) } returns 0
        coEvery { workoutDao.getWeightedAvgHr(any(), any()) } returns 0f
        coEvery { workoutDao.getWorkoutsInRange(any(), any()) } returns emptyList()

        coEvery { hrvDao.getSleepRmssdValues(any()) } returns listOf(60f, 60f, 60f)
        coEvery { hrvDao.getSleepRmssdValuesSince(any(), any()) } returns listOf(60f, 60f, 60f)
        coEvery { hrvDao.getSleepRmssdForSession(any()) } returns listOf(60f, 60f)
        coEvery { hrvDao.getRmssdInTimeRange(any(), any()) } returns listOf(60f, 60f)
        coEvery { hrvDao.getSleepRmssdValuesForSessions(any()) } returns listOf(60f, 60f, 60f)
        coEvery { hrvDao.getSleepRmssdForSessionsMap(any()) } returns emptyMap()

        coEvery { heartRateDao.getAvgSleepHrPerSession(any()) } returns listOf(55, 55, 55)
        coEvery { heartRateDao.getAvgSleepHr(any()) } returns 55
        coEvery { heartRateDao.getAvgSleepHrForSessions(any()) } returns emptyMap()
        coEvery { heartRateDao.getMinHrInRange(any(), any()) } returns 50
        coEvery { heartRateDao.getByTimeRange(any(), any()) } returns emptyList()
        coEvery { heartRateDao.getMinHrTimestamp(any()) } returns null
        coEvery { heartRateDao.getSleepHrSampleCount(any()) } returns 300
        coEvery { heartRateDao.getSleepHrSampleAtOffset(any(), any()) } returns 50
        coEvery { heartRateDao.getSleepHrSamplesForSession(any()) } returns listOf(48, 50, 52, 54, 56, 58, 60)
        coEvery { heartRateDao.getSleepHrSamplesForSessions(any()) } returns emptyList()
        coEvery { heartRateDao.getSleepHrProjectionForSessions(any()) } returns emptyList()

        coEvery { dailySummaryDao.getByDate(any()) } returns null
        coEvery { dailySummaryDao.getByDates(any()) } returns emptyList()
        coEvery { dailySummaryDao.getEverydayTrimpPoints(any(), any()) } returns emptyList()
        coEvery { dailySummaryDao.upsert(any()) } returns Unit

        scoringCalculator =
            CompositeScoringCalculator(
                SleepScoringStrategy(LoadScoringStrategy()),
                RasScoringStrategy(),
                LoadScoringStrategy(),
            )

        val scoringHistoryRepository =
            ScoringHistoryRepositoryImpl(heartRateDao, hrvDao, sleepSessionDao, dailySummaryDao)
        val baselineComputer = BaselineComputer(scoringHistoryRepository, scoringCalculator)
        val scoringConfigFactory = ScoringConfigFactory()
        val encryptionManager = mockk<EncryptionManager>(relaxed = true)
        val hrvResolver = CurrentNightHrvResolver(scoringHistoryRepository)
        val sleepPercentileRhrCalculator = SleepPercentileRhrCalculator(scoringHistoryRepository)
        val nadirAnalyzer = SleepNadirAnalyzer(scoringHistoryRepository, scoringCalculator)
        val coverageValidator = HrCoverageValidator()
        val computeSleepMetricsUseCase =
            ComputeSleepMetricsUseCase(
                baselineComputer,
                dailySummaryDao,
                heartRateDao,
                scoringCalculator,
                scoringConfigFactory,
                encryptionManager,
                hrvResolver,
                sleepPercentileRhrCalculator,
                nadirAnalyzer,
                coverageValidator,
            )
        val computeWorkoutTrimpUseCase = ComputeWorkoutTrimpUseCase()
        val oxygenSaturationRecordDao = mockk<OxygenSaturationRecordDao>(relaxed = true)

        repo =
            ScoringRepositoryImpl(
                workoutDao = workoutDao,
                sleepSessionDao = sleepSessionDao,
                dailySummaryDao = dailySummaryDao,
                settingsRepo = settingsRepo,
                scoringCalculator = scoringCalculator,
                baselineComputer = baselineComputer,
                computeSleepMetricsUseCase = computeSleepMetricsUseCase,
                scoringConfigFactory = scoringConfigFactory,
                computeWorkoutTrimpUseCase = computeWorkoutTrimpUseCase,
                heartRateDao = heartRateDao,
                weightRecordDao = weightRecordDao,
                bodyFatRecordDao = bodyFatRecordDao,
                bloodPressureRecordDao = bloodPressureRecordDao,
                oxygenSaturationRecordDao = oxygenSaturationRecordDao,
                sleepPercentileRhrCalculator = sleepPercentileRhrCalculator,
                scoringHistoryRepository = scoringHistoryRepository,
            )
    }

    @Test
    fun `profile differentiation produces different RAS gains`() =
        runTest {
            val today = LocalDate.now()
            val dayMidnight = today.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            val nextDayMidnight =
                today
                    .plusDays(1)
                    .atStartOfDay(java.time.ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()

            val workout =
                app.readylytics.health.data.local.entity.WorkoutRecordEntity(
                    id = "w1",
                    startTime = dayMidnight + 3600000,
                    endTime = dayMidnight + 7200000,
                    exerciseType = "RUN",
                    durationMinutes = 60,
                    zone1Minutes = 0f,
                    zone2Minutes = 0f,
                    zone3Minutes = 0f,
                    zone4Minutes = 60f,
                    zone5Minutes = 0f,
                    trimp = 100f,
                    avgHr = 150f,
                )
            coEvery { workoutDao.getWorkoutsInRange(dayMidnight, nextDayMidnight) } returns listOf(workout)

            val samples =
                (0 until 60).map { i ->
                    app.readylytics.health.data.local.entity.HeartRateRecordEntity(
                        id = "s$i",
                        timestampMs = workout.startTime + i * 60000L,
                        beatsPerMinute = 150,
                        recordType = "EXERCISE",
                        sessionId = "w1",
                    )
                }
            coEvery { heartRateDao.getByTimeRange(any(), any()) } returns samples

            val capturedSummaries = mutableListOf<DailySummaryEntity>()
            coEvery { dailySummaryDao.upsert(capture(capturedSummaries)) } returns Unit

            // Profile: ATHLETE (SF=0.15)
            every { settingsRepo.userPreferences } returns
                MutableStateFlow(
                    UserPreferences(
                        physiologyProfile = PhysiologyProfile.ATHLETE,
                        rasScalingFactor = 0.15f,
                        maxHeartRate = 190,
                        age = 30,
                        gender = Gender.fromString("Male"),
                    ),
                )
            repo.computeAndPersistDailySummary(today)

            // Profile: SEDENTARY (SF=0.25)
            every { settingsRepo.userPreferences } returns
                MutableStateFlow(
                    UserPreferences(
                        physiologyProfile = PhysiologyProfile.SEDENTARY,
                        rasScalingFactor = 0.25f,
                        maxHeartRate = 190,
                        age = 30,
                        gender = Gender.fromString("Male"),
                    ),
                )
            repo.computeAndPersistDailySummary(today)

            coVerify(exactly = 2) { dailySummaryDao.upsert(any()) }
            val athleteRas = capturedSummaries[0].rasWorkoutOnly ?: 0f
            val sedentaryRas = capturedSummaries[1].rasWorkoutOnly ?: 0f
            assert(athleteRas < sedentaryRas) {
                "Athlete ($athleteRas) should earn fewer points than Sedentary ($sedentaryRas)"
            }
        }

    @Test
    fun `baseline calculation excludes invalid nights`() =
        runTest {
            val validSession = makeSleepSession("valid", 1)
            val shortSession = makeSleepSession("short", 2).copy(durationMinutes = 120) // 2h < 4h threshold
            val sessions = listOf(validSession, shortSession)
            coEvery { sleepSessionDao.getSince(any()) } returns sessions
            coEvery { sleepSessionDao.getBetween(any(), any()) } returns sessions

            coEvery { hrvDao.getSleepRmssdForSessionsMap(any()) } returns
                mapOf("valid" to listOf(60f), "short" to listOf(60f))
            coEvery { heartRateDao.getAvgSleepHrForSessions(any()) } returns
                mapOf("valid" to 55, "short" to 55)

            repo.computeAndPersistDailySummary(LocalDate.now())

            // Should only fetch HRV samples for the valid session for baseline median
            coVerify { hrvDao.getSleepRmssdForSessionsMap(listOf("valid")) }
        }

    @Test
    fun `timezone jump suppresses late nadir penalty`() =
        runTest {
            val todaySession =
                makeSleepSession("today", 0).copy(
                    startZoneOffsetSeconds = 3600, // UTC+1
                    endZoneOffsetSeconds = 3600,
                )
            val prevSession =
                makeSleepSession("prev", 1).copy(
                    startZoneOffsetSeconds = -18000, // UTC-5
                    endZoneOffsetSeconds = -18000,
                )
            coEvery { sleepSessionDao.getSessionEndingInRange(any(), any()) } returns todaySession
            coEvery { sleepSessionDao.getSince(any()) } returns listOf(todaySession, prevSession)
            coEvery { sleepSessionDao.getBetween(any(), any()) } returns listOf(todaySession, prevSession)

            val sessionDurationMs = todaySession.durationMinutes * 60 * 1000L
            val lateNadirTs = todaySession.startTime + (sessionDurationMs * 0.8).toLong()
            coEvery { heartRateDao.getMinHrTimestamp("today") } returns lateNadirTs

            val summarySlot = io.mockk.slot<DailySummaryEntity>()
            coEvery { dailySummaryDao.upsert(capture(summarySlot)) } returns Unit

            repo.computeAndPersistDailySummary(LocalDate.now())

            val flags = summarySlot.captured.recoveryFlags ?: ""
            assert(!flags.contains("NADIR_DELAYED")) {
                "NADIR_DELAYED should be suppressed during travel, but found in flags: $flags"
            }
        }

    @Test
    fun `batch fetch replaces per-session getMinHrInRange calls`() =
        runTest {
            repo.computeAndPersistDailySummary(LocalDate.now())
            // Two batch fetches: one for the everyday-HR load window (full day) and one for the
            // sleep-metrics wake-HR window. The key invariant is that no per-session getMinHrInRange
            // calls are used.
            coVerify(exactly = 2) { heartRateDao.getByTimeRange(any(), any()) }
            coVerify(exactly = 0) { heartRateDao.getMinHrInRange(any(), any()) }
        }

    @Test
    fun `baseline validation uses bulk-fetch DAO methods instead of per-session calls`() =
        runTest {
            val sessions = (1..5).map { makeSleepSession("s$it", it) }
            coEvery { sleepSessionDao.getSince(any()) } returns sessions
            coEvery { sleepSessionDao.getBetween(any(), any()) } returns sessions
            coEvery { hrvDao.getSleepRmssdForSessionsMap(any()) } returns sessions.associate { it.id to listOf(60f) }
            coEvery { heartRateDao.getAvgSleepHrForSessions(any()) } returns sessions.associate { it.id to 55 }

            repo.computeAndPersistDailySummary(LocalDate.now())

            coVerify(atLeast = 1) { hrvDao.getSleepRmssdForSessionsMap(any()) }
            coVerify(atLeast = 1) { heartRateDao.getAvgSleepHrForSessions(any()) }
            coVerify(exactly = 1) { hrvDao.getSleepRmssdForSession(any()) }
            coVerify(exactly = 0) { heartRateDao.getAvgSleepHr(any()) }
        }

    @Test
    fun `result is persisted exactly once`() =
        runTest {
            repo.computeAndPersistDailySummary(LocalDate.now())
            coVerify(exactly = 1) { dailySummaryDao.upsert(any<DailySummaryEntity>()) }
        }

    @Test
    fun `is persisted even when insufficient sessions for calibration`() =
        runTest {
            coEvery { sleepSessionDao.countSince(any()) } returns 3
            repo.computeAndPersistDailySummary(LocalDate.now())
            coVerify(exactly = 1) { dailySummaryDao.upsert(any()) }
        }

    @Test
    fun `day 1 baseline initialization matches daily values`() =
        runTest {
            val today = LocalDate.now()
            val todaySession = makeSleepSession("today", 0)

            // Day 1 setup: only 1 session exists
            coEvery { sleepSessionDao.getSessionEndingInRange(any(), any()) } returns todaySession
            coEvery { sleepSessionDao.getSince(any()) } returns listOf(todaySession)
            coEvery { sleepSessionDao.getBetween(any(), any()) } returns listOf(todaySession)
            coEvery { sleepSessionDao.countSince(any()) } returns 1

            // Mock 10 heart rate samples of 53 bpm to satisfy minimum size requirement of 10
            val hrSamples = (1..10).map { SleepHrSample("today", 53) }
            coEvery { heartRateDao.getSleepHrProjectionForSessions(listOf("today")) } returns hrSamples

            // For daily average RHR calculation (avgRhr)
            coEvery { heartRateDao.getSleepHrSamplesForSession("today") } returns (1..10).map { 53 }

            // Mock 1 HRV sample of 32 ms
            coEvery { hrvDao.getSleepRmssdForSessionsMap(listOf("today")) } returns mapOf("today" to listOf(32f))
            coEvery { hrvDao.getSleepRmssdForSession("today") } returns listOf(32f)

            val summarySlot = io.mockk.slot<DailySummaryEntity>()
            coEvery { dailySummaryDao.upsert(capture(summarySlot)) } returns Unit

            repo.computeAndPersistDailySummary(today)

            val result = summarySlot.captured
            kotlin.test.assertEquals(53f, result.rhrBpm, "RHR baseline should match daily values on Day 1")
            kotlin.test.assertEquals(32, result.hrvBaseline, "HRV baseline should match daily values on Day 1")
        }

    @Test
    fun `HRV baseline is stable across multiple identical calculations`() =
        runTest {
            val today = LocalDate.now()
            val todaySession = makeSleepSession("today", 0)

            coEvery { sleepSessionDao.getSessionEndingInRange(any(), any()) } returns todaySession
            coEvery { sleepSessionDao.getSince(any()) } returns listOf(todaySession)
            coEvery { sleepSessionDao.getBetween(any(), any()) } returns listOf(todaySession)
            coEvery { sleepSessionDao.countSince(any()) } returns 1

            val hrSamples = (1..10).map { SleepHrSample("today", 53) }
            coEvery { heartRateDao.getSleepHrProjectionForSessions(listOf("today")) } returns hrSamples
            coEvery { heartRateDao.getSleepHrSamplesForSession("today") } returns (1..10).map { 53 }

            // Sleep RMSSD map has consistent 32f
            coEvery { hrvDao.getSleepRmssdForSessionsMap(listOf("today")) } returns mapOf("today" to listOf(32f))
            coEvery { hrvDao.getSleepRmssdForSession("today") } returns listOf(32f)

            val summarySlot1 = io.mockk.slot<DailySummaryEntity>()
            coEvery { dailySummaryDao.upsert(capture(summarySlot1)) } returns Unit
            repo.computeAndPersistDailySummary(today)
            val firstHrvBaseline = summarySlot1.captured.hrvBaseline

            val summarySlot2 = io.mockk.slot<DailySummaryEntity>()
            coEvery { dailySummaryDao.upsert(capture(summarySlot2)) } returns Unit
            repo.computeAndPersistDailySummary(today)
            val secondHrvBaseline = summarySlot2.captured.hrvBaseline

            kotlin.test.assertEquals(
                firstHrvBaseline,
                secondHrvBaseline,
                "HRV baseline must remain stable and identical",
            )
        }
}
