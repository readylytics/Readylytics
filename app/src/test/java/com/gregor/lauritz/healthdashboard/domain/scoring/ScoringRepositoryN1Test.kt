package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HeartRateDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HrvDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.data.local.dao.WorkoutDao
import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferencesRepository
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
    private lateinit var prefsRepo: UserPreferencesRepository
    private lateinit var repo: ScoringRepository

    private val baseMs = System.currentTimeMillis()
    private val todayMidnight = LocalDate.now()
        .atStartOfDay(java.time.ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

    private fun makeSleepSession(id: String, offsetDays: Int) = SleepSessionEntity(
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
        prefsRepo = mockk()

        every { prefsRepo.userPreferences } returns MutableStateFlow(UserPreferences())

        // Provide enough sessions to pass the calibration guard (MIN_SESSIONS_FOR_CALIBRATION = 7)
        coEvery { sleepSessionDao.countSince(any()) } returns 10

        val historicSessions = (1..9).map { makeSleepSession("historic_$it", it) }
        val todaySession = makeSleepSession("today", 0)

        coEvery { sleepSessionDao.getSessionEndingInRange(any(), any()) } returns todaySession
        coEvery { sleepSessionDao.getSince(any()) } returns historicSessions + todaySession

        coEvery { workoutDao.getDailyTrimp(any(), any(), any()) } returns emptyList()
        coEvery { workoutDao.getTotalTrimp(any(), any()) } returns 0f
        coEvery { workoutDao.getTotalDurationMinutes(any(), any()) } returns 0
        coEvery { workoutDao.getWeightedAvgHr(any(), any()) } returns 0f

        coEvery { hrvDao.getSleepRmssdValues(any()) } returns listOf(60f, 60f, 60f)
        coEvery { hrvDao.getSleepRmssdForSession(any()) } returns listOf(60f, 60f)

        coEvery { heartRateDao.getAvgSleepHrPerSession(any()) } returns listOf(55, 55, 55)
        coEvery { heartRateDao.getAvgSleepHr(any()) } returns 55
        coEvery { heartRateDao.getMinHrInRange(any(), any()) } returns 50
        coEvery { heartRateDao.getByTimeRange(any(), any()) } returns emptyList()
        coEvery { heartRateDao.getMinHrTimestamp(any()) } returns null

        coEvery { dailySummaryDao.getByDate(any()) } returns null
        coEvery { dailySummaryDao.upsert(any()) } returns Unit

        repo = ScoringRepository(workoutDao, sleepSessionDao, heartRateDao, hrvDao, dailySummaryDao, prefsRepo)
    }

    @Test
    fun `batch fetch replaces per-session getMinHrInRange calls`() = runTest {
        repo.computeAndPersistDailySummary(LocalDate.now())

        // getByTimeRange called once for the full batch window — not once per historic session
        coVerify(exactly = 1) { heartRateDao.getByTimeRange(any(), any()) }

        // getMinHrInRange called only once — for the current session's personal window
        coVerify(exactly = 1) { heartRateDao.getMinHrInRange(any(), any()) }
    }

    @Test
    fun `result is persisted exactly once`() = runTest {
        repo.computeAndPersistDailySummary(LocalDate.now())
        coVerify(exactly = 1) { dailySummaryDao.upsert(any<DailySummaryEntity>()) }
    }

    @Test
    fun `is persisted even when insufficient sessions for calibration`() = runTest {
        coEvery { sleepSessionDao.countSince(any()) } returns 3
        repo.computeAndPersistDailySummary(LocalDate.now())
        // Should still upsert the PAI-only summary
        coVerify(exactly = 1) { dailySummaryDao.upsert(any()) }
    }
}
