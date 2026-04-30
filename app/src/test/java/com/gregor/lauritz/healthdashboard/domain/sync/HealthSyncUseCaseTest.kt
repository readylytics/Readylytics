package com.gregor.lauritz.healthdashboard.domain.sync

import com.gregor.lauritz.healthdashboard.data.healthconnect.HealthConnectRepository
import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HeartRateDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HrvDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.data.local.dao.WorkoutDao
import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
import com.gregor.lauritz.healthdashboard.data.preferences.AppConfigRepository
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferencesRepository
import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringRepository
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class HealthSyncUseCaseTest {
    private val hcRepo = mockk<HealthConnectRepository>(relaxed = true)
    private val sleepDao = mockk<SleepSessionDao>(relaxed = true)
    private val heartRateDao = mockk<HeartRateDao>(relaxed = true)
    private val hrvDao = mockk<HrvDao>(relaxed = true)
    private val workoutDao = mockk<WorkoutDao>(relaxed = true)
    private val dailySummaryDao = mockk<DailySummaryDao>(relaxed = true)
    private val prefsRepo = mockk<UserPreferencesRepository>(relaxed = true)
    private val appConfigRepo = mockk<AppConfigRepository>(relaxed = true)
    private val scoringRepository = mockk<ScoringRepository>(relaxed = true)

    private lateinit var useCase: HealthSyncUseCase

    @Before
    fun setup() {
        useCase = HealthSyncUseCase(
            hcRepo, sleepDao, heartRateDao, hrvDao, workoutDao,
            dailySummaryDao, prefsRepo, appConfigRepo, scoringRepository
        )
        every { prefsRepo.userPreferences } returns flowOf(UserPreferences())
    }

    @Test
    fun `sync processes days in chronological order`() = runTest {
        val windowDays = 3
        val today = LocalDate.now(ZoneId.systemDefault())
        val day0 = today.minusDays(2)
        val day1 = today.minusDays(1)
        val day2 = today

        coEvery { scoringRepository.computeDailySummary(any()) } returns DailySummaryEntity(dateMidnightMs = 0L)

        useCase.sync(windowDays = windowDays)

        coVerifyOrder {
            scoringRepository.computeDailySummary(day0)
            dailySummaryDao.upsert(any())
            scoringRepository.computeDailySummary(day1)
            dailySummaryDao.upsert(any())
            scoringRepository.computeDailySummary(day2)
            dailySummaryDao.upsert(any())
        }
    }
}
