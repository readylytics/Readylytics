package com.gregor.lauritz.healthdashboard.domain.sync

import com.gregor.lauritz.healthdashboard.data.local.dao.BloodPressureRecordDao
import com.gregor.lauritz.healthdashboard.data.local.dao.BodyFatRecordDao
import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HeartRateDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HrvDao
import com.gregor.lauritz.healthdashboard.data.local.dao.OxygenSaturationRecordDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepStageDao
import com.gregor.lauritz.healthdashboard.data.local.dao.WeightRecordDao
import com.gregor.lauritz.healthdashboard.data.local.dao.WorkoutDao
import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
import com.gregor.lauritz.healthdashboard.domain.repository.HealthConnectRepository
import com.gregor.lauritz.healthdashboard.domain.repository.ScoringRepository
import com.gregor.lauritz.healthdashboard.domain.repository.TransactionRunner
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class HealthSyncUseCaseTest {
    private val hcRepo = mockk<HealthConnectRepository>(relaxed = true)
    private val sleepDao = mockk<SleepSessionDao>(relaxed = true)
    private val sleepStageDao = mockk<SleepStageDao>(relaxed = true)
    private val heartRateDao = mockk<HeartRateDao>(relaxed = true)
    private val hrvDao = mockk<HrvDao>(relaxed = true)
    private val workoutDao = mockk<WorkoutDao>(relaxed = true)
    private val dailySummaryDao = mockk<DailySummaryDao>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val scoringRepository = mockk<ScoringRepository>(relaxed = true)
    private val transactionRunner = mockk<TransactionRunner>(relaxed = true)
    private val weightRecordDao = mockk<WeightRecordDao>(relaxed = true)
    private val bodyFatRecordDao = mockk<BodyFatRecordDao>(relaxed = true)
    private val bloodPressureRecordDao = mockk<BloodPressureRecordDao>(relaxed = true)
    private val oxygenSaturationRecordDao = mockk<OxygenSaturationRecordDao>(relaxed = true)

    private lateinit var useCase: HealthSyncUseCase

    @Before
    fun setup() {
        coEvery { transactionRunner.runInTransaction<Any>(any()) } coAnswers {
            val block = firstArg<suspend () -> Any>()
            block()
        }

        useCase =
            HealthSyncUseCase(
                hcRepo = hcRepo,
                sleepSessionDao = sleepDao,
                sleepStageDao = sleepStageDao,
                heartRateDao = heartRateDao,
                hrvDao = hrvDao,
                workoutDao = workoutDao,
                weightRecordDao = weightRecordDao,
                bodyFatRecordDao = bodyFatRecordDao,
                bloodPressureRecordDao = bloodPressureRecordDao,
                dailySummaryDao = dailySummaryDao,
                settingsRepo = settingsRepo,
                scoringRepository = scoringRepository,
                transactionRunner = transactionRunner,
                oxygenSaturationRecordDao = oxygenSaturationRecordDao,
            )
        every { settingsRepo.userPreferences } returns flowOf(UserPreferences())
    }

    @Test
    fun `sync processes days in chronological order`() =
        runTest {
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

    @Test
    fun `sync fetches and upserts all heart-related record types`() =
        runTest {
            coEvery { scoringRepository.computeDailySummary(any()) } returns DailySummaryEntity(dateMidnightMs = 0L)

            // Mock non-empty returns to ensure mapping logic is triggered
            coEvery { hcRepo.readHeartRateSamples(any(), any()) } returns listOf(mockk(relaxed = true))
            coEvery { hcRepo.readHrvSamples(any(), any()) } returns listOf(mockk(relaxed = true))
            coEvery { hcRepo.readSteps(any(), any()) } returns 0L

            useCase.sync()

            coVerify {
                hcRepo.readHeartRateSamples(any(), any())
                hcRepo.readHrvSamples(any(), any())
                hcRepo.readSteps(any(), any())
                heartRateDao.upsertAll(any())
                hrvDao.upsertAll(any())
            }
        }

    @Test
    fun `daily sync windowDays 1 fetches samples from yesterday to cover cross-midnight sleep`() =
        runTest {
            coEvery { scoringRepository.computeDailySummary(any()) } returns DailySummaryEntity(dateMidnightMs = 0L)

            val hrvFromSlot = slot<Instant>()
            val hrFromSlot = slot<Instant>()
            coEvery { hcRepo.readHrvSamples(capture(hrvFromSlot), any()) } returns emptyList()
            coEvery { hcRepo.readHeartRateSamples(capture(hrFromSlot), any()) } returns emptyList()

            useCase.sync(windowDays = 1)

            // Last night's sleep session begins the previous evening (before midnight); the
            // ingestion fetch must reach back one extra day so its pre-midnight HR/HRV samples
            // are captured. windowDays = 1 => fetch from yesterday 00:00, not today 00:00.
            val zoneId = ZoneId.systemDefault()
            val yesterdayMidnight = LocalDate.now(zoneId).minusDays(1).atStartOfDay(zoneId).toInstant()
            assertEquals(yesterdayMidnight, hrvFromSlot.captured)
            assertEquals(yesterdayMidnight, hrFromSlot.captured)
        }

    @Test
    fun `sync runs migration when baseline version is below 2`() =
        runTest {
            // Setup: stale rows exist
            coEvery { dailySummaryDao.countRowsWithBaselineVersionBelow(2) } returns 5
            coEvery { dailySummaryDao.getEarliestDateMs() } returns 1717192800000L // 2024-06-01
            coEvery { scoringRepository.computeDailySummary(any()) } returns DailySummaryEntity(dateMidnightMs = 0L)

            useCase.sync()

            coVerify {
                dailySummaryDao.clearFrozenBaselines()
                dailySummaryDao.setBaselineVersion(2)
            }
        }

    @Test
    fun `sync skips migration when all rows are at version 2`() =
        runTest {
            coEvery { dailySummaryDao.countRowsWithBaselineVersionBelow(2) } returns 0
            coEvery { scoringRepository.computeDailySummary(any()) } returns DailySummaryEntity(dateMidnightMs = 0L)

            useCase.sync()

            coVerify(exactly = 0) {
                dailySummaryDao.clearFrozenBaselines()
            }
        }

    @Test
    fun `sync migration recomputes scores for historical days`() =
        runTest {
            val earliestMs = 1717192800000L // 2024-06-01
            coEvery { dailySummaryDao.countRowsWithBaselineVersionBelow(2) } returns 1
            coEvery { dailySummaryDao.getEarliestDateMs() } returns earliestMs
            coEvery { scoringRepository.computeDailySummary(any()) } returns DailySummaryEntity(dateMidnightMs = 0L)

            useCase.sync()

            // Verify it started from June 1st
            coVerify {
                scoringRepository.computeDailySummary(LocalDate.of(2024, 6, 1))
            }
        }
}
