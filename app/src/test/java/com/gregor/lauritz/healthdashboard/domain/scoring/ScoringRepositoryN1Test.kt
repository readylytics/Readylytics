package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HeartRateDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HrvDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.data.local.dao.WorkoutDao
import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import com.gregor.lauritz.healthdashboard.data.preferences.Gender
import com.gregor.lauritz.healthdashboard.data.preferences.PhysiologyProfile
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
import com.gregor.lauritz.healthdashboard.data.repository.ScoringRepositoryImpl
import com.gregor.lauritz.healthdashboard.data.security.EncryptionManager
import com.gregor.lauritz.healthdashboard.domain.repository.ScoringRepository
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
    private lateinit var repo: ScoringRepository

    private val baseMs = System.currentTimeMillis()
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
    )

    @Before
    fun setUp() {
        heartRateDao = mockk()
        sleepSessionDao = mockk()
        hrvDao = mockk()
        workoutDao = mockk()
        dailySummaryDao = mockk()
        settingsRepo = mockk()

        every { settingsRepo.userPreferences } returns
            MutableStateFlow(UserPreferences(physiologyProfile = PhysiologyProfile.GENERAL))

        // Provide enough sessions to pass the calibration guard (MIN_SESSIONS_FOR_CALIBRATION = 7)
        coEvery { sleepSessionDao.countSince(any()) } returns 10

        val historicSessions = (1..9).map { makeSleepSession("historic_$it", it) }
        val todaySession = makeSleepSession("today", 0)

        coEvery { sleepSessionDao.getSessionEndingInRange(any(), any()) } returns todaySession
        coEvery { sleepSessionDao.getSince(any()) } returns historicSessions + todaySession

        coEvery { workoutDao.getDailyTrimp(any(), any(), any()) } returns emptyList()
        coEvery { workoutDao.getDailyTrmpByEpochDay(any(), any(), any()) } returns emptyMap()
        coEvery { workoutDao.getTotalTrimp(any(), any()) } returns 0f
        coEvery { workoutDao.getTotalDurationMinutes(any(), any()) } returns 0
        coEvery { workoutDao.getWeightedAvgHr(any(), any()) } returns 0f

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

        coEvery { dailySummaryDao.getByDate(any()) } returns null
        coEvery { dailySummaryDao.upsert(any()) } returns Unit
        coEvery { workoutDao.upsertAll(any()) } returns Unit

        scoringCalculator = ScoringCalculatorImpl()
        val baselineComputer = BaselineComputer(heartRateDao, hrvDao, sleepSessionDao, scoringCalculator)
        val scoringConfigFactory = ScoringConfigFactory() // Real factory is fine as it's pure logic mostly
        val encryptionManager = mockk<EncryptionManager>(relaxed = true)
        val computeSleepMetricsUseCase =
            ComputeSleepMetricsUseCase(
                baselineComputer,
                dailySummaryDao,
                hrvDao,
                heartRateDao,
                sleepSessionDao,
                scoringCalculator,
                scoringConfigFactory,
                encryptionManager,
            )
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
                heartRateDao,
                hrvDao,
            )

        coEvery { workoutDao.getWorkoutsInRange(any(), any()) } returns emptyList()
    }

    @Test
    fun `profile differentiation produces different PAI gains`() =
        runTest {
            val today = LocalDate.now()
            val dayMidnight = today.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            val nextDayMidnight =
                today
                    .plusDays(
                        1,
                    ).atStartOfDay(java.time.ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()

            // 1-hour workout at 150 bpm
            val workout =
                com.gregor.lauritz.healthdashboard.data.local.entity.WorkoutRecordEntity(
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

            // HR Samples for the workout
            val samples =
                (0 until 60).map { i ->
                    com.gregor.lauritz.healthdashboard.data.local.entity.HeartRateRecordEntity(
                        id = "s$i",
                        timestampMs = workout.startTime + i * 60000L,
                        beatsPerMinute = 150,
                        recordType = "EXERCISE",
                        sessionId = "w1",
                    )
                }
            coEvery { heartRateDao.getByTimeRange(workout.startTime, workout.endTime) } returns samples

            val capturedSummaries = mutableListOf<DailySummaryEntity>()
            coEvery { dailySummaryDao.upsert(capture(capturedSummaries)) } returns Unit

            // Profile: ATHLETE (SF=0.15)
            every { settingsRepo.userPreferences } returns
                MutableStateFlow(
                    UserPreferences(
                        physiologyProfile = PhysiologyProfile.ATHLETE,
                        paiScalingFactor = 0.15f,
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
                        paiScalingFactor = 0.25f,
                        maxHeartRate = 190,
                        age = 30,
                        gender = Gender.fromString("Male"),
                    ),
                )

            repo.computeAndPersistDailySummary(today)

            coVerify(exactly = 2) { dailySummaryDao.upsert(any()) }

            val athletePai = capturedSummaries[0].paiScore ?: 0f
            val sedentaryPai = capturedSummaries[1].paiScore ?: 0f

            assert(
                athletePai < sedentaryPai,
            ) { "Athlete ($athletePai) should earn fewer points than Sedentary ($sedentaryPai)" }
        }

    @Test
    fun `baseline calculation excludes invalid nights`() =
        runTest {
            val validSession = makeSleepSession("valid", 1)
            val shortSession = makeSleepSession("short", 2).copy(durationMinutes = 120) // 2h < 4h threshold
            val sessions = listOf(validSession, shortSession)

            coEvery { sleepSessionDao.getSince(any()) } returns sessions

            // Mock bulk fetches for these specific sessions
            coEvery { hrvDao.getSleepRmssdForSessionsMap(match { it.containsAll(listOf("valid", "short")) }) } returns
                mapOf("valid" to listOf(60f), "short" to listOf(60f))
            coEvery {
                heartRateDao.getAvgSleepHrForSessions(
                    match { it.containsAll(listOf("valid", "short")) },
                )
            } returns
                mapOf("valid" to 55, "short" to 55)

            repo.computeAndPersistDailySummary(LocalDate.now())

            // Should only fetch HRV samples for the valid session for baseline median
            coVerify { hrvDao.getSleepRmssdValuesForSessions(listOf("valid")) }
            coVerify(exactly = 0) { hrvDao.getSleepRmssdValuesForSessions(match { it.contains("short") }) }
        }

    @Test
    fun `timezone jump suppresses late nadir penalty`() =
        runTest {
            // Mock current session as having a late nadir
            val todaySession =
                makeSleepSession("today", 0).copy(
                    startZoneOffsetSeconds = 3600, // UTC+1
                    endZoneOffsetSeconds = 3600,
                )
            val prevSession =
                makeSleepSession("prev", 1).copy(
                    startZoneOffsetSeconds = -18000, // UTC-5 (e.g. travel from NY to London)
                    endZoneOffsetSeconds = -18000,
                )

            coEvery { sleepSessionDao.getSessionEndingInRange(any(), any()) } returns todaySession
            coEvery { sleepSessionDao.getSince(any()) } returns listOf(todaySession, prevSession)

            // Mock a late nadir timestamp (e.g. 80% into the session)
            val sessionDurationMs = todaySession.durationMinutes * 60 * 1000L
            val lateNadirTs = todaySession.startTime + (sessionDurationMs * 0.8).toLong()
            coEvery { heartRateDao.getMinHrTimestamp("today") } returns lateNadirTs

            repo.computeAndPersistDailySummary(LocalDate.now())

            // Capture persisted summary and check flags
            val summarySlot = io.mockk.slot<DailySummaryEntity>()
            coVerify { dailySummaryDao.upsert(capture(summarySlot)) }

            val flags = summarySlot.captured.recoveryFlags ?: ""
            // NADIR_DELAYED should be suppressed due to the timezone jump
            assert(!flags.contains("NADIR_DELAYED")) {
                "NADIR_DELAYED should be suppressed during travel, but found in flags: $flags"
            }
        }

    @Test
    fun `batch fetch replaces per-session getMinHrInRange calls`() =
        runTest {
            repo.computeAndPersistDailySummary(LocalDate.now())

            // getByTimeRange called once for the full batch window — not once per historic session
            coVerify(exactly = 1) { heartRateDao.getByTimeRange(any(), any()) }

            // getMinHrInRange called only once — for the current session's personal window
            coVerify(exactly = 1) { heartRateDao.getMinHrInRange(any(), any()) }
        }

    @Test
    fun `baseline validation uses bulk-fetch DAO methods instead of per-session calls`() =
        runTest {
            val sessions = (1..5).map { makeSleepSession("s$it", it) }
            coEvery { sleepSessionDao.getSince(any()) } returns sessions

            // Mock bulk responses for any session list
            coEvery { hrvDao.getSleepRmssdForSessionsMap(any()) } returns sessions.associate { it.id to listOf(60f) }
            coEvery { heartRateDao.getAvgSleepHrForSessions(any()) } returns sessions.associate { it.id to 55 }

            repo.computeAndPersistDailySummary(LocalDate.now())

            // Verify bulk fetch was called (at least once for each step: computeHrvBaseline, computeHrvWindows)
            coVerify(atLeast = 1) { hrvDao.getSleepRmssdForSessionsMap(any()) }
            coVerify(atLeast = 1) { heartRateDao.getAvgSleepHrForSessions(any()) }

            // Verify legacy per-session methods were NOT called for historical sessions
            // (They are still allowed for the single "today" session in calculateSleepMetrics)
            coVerify(atMost = 1) { hrvDao.getSleepRmssdForSession(any()) }
            coVerify(atMost = 1) { heartRateDao.getAvgSleepHr(any()) }
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
            // Should still upsert the PAI-only summary
            coVerify(exactly = 1) { dailySummaryDao.upsert(any()) }
        }
}
