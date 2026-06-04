package com.gregor.lauritz.healthdashboard.data.repository

import com.gregor.lauritz.healthdashboard.data.local.dao.*
import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
import com.gregor.lauritz.healthdashboard.domain.scoring.*
import com.gregor.lauritz.healthdashboard.domain.scoring.sleep.SleepPercentileRhrCalculator
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertNotEquals

class ScoringRepositoryImplTest {
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
        every { settingsRepo.userPreferences } returns flowOf(UserPreferences())
        coEvery { dailySummaryDao.getByDate(any()) } returns null
        coEvery { sleepSessionDao.countSince(any()) } returns 10
        coEvery { baselineComputer.computeAdaptiveBaselineRhrBpmBetween(any(), any(), any()) } returns 60f
    }

    @Test
    fun `computeDailySummary returns different baseline for consecutive days`() =
        runTest {
            val today = LocalDate.now()
            val yesterday = today.minusDays(1)
            val zoneId = ZoneId.systemDefault()

            val todayMs = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val tomorrowMs =
                today
                    .plusDays(1)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val yesterdayMs = yesterday.atStartOfDay(zoneId).toInstant().toEpochMilli()

            // Mock RHR baseline for today
            coEvery {
                baselineComputer.computeAdaptiveBaselineRhrBpmBetween(todayMs, tomorrowMs, any())
            } returns 55f

            // Mock RHR baseline for yesterday
            coEvery {
                baselineComputer.computeAdaptiveBaselineRhrBpmBetween(yesterdayMs, todayMs, any())
            } returns 60f

            // Mock HRV windows to ensure hrvMuMssd is also different
            coEvery {
                baselineComputer.computeHrvWindowsBetween(todayMs, tomorrowMs, any())
            } returns BaselineComputer.HrvWindows(listOf(60f, 62f), emptyList(), emptyList(), emptyList())

            coEvery {
                baselineComputer.computeHrvWindowsBetween(yesterdayMs, todayMs, any())
            } returns BaselineComputer.HrvWindows(listOf(70f, 72f), emptyList(), emptyList(), emptyList())

            // Ensure use case returns something
            coEvery {
                computeSleepMetricsUseCase(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns
                com.gregor.lauritz.healthdashboard.domain.model.Result
                    .success(DailySummaryEntity(0L))

            val resultToday = repo.computeDailySummary(today)
            val resultYesterday = repo.computeDailySummary(yesterday)

            assertNotEquals(resultToday.rhrBpm, resultYesterday.rhrBpm, "RHR baseline should differ")
            assertNotEquals(resultToday.hrvMuMssd, resultYesterday.hrvMuMssd, "HRV mu should differ")
        }

    @Test
    fun `computeDailySummary prioritizes rhrBpm over restingHeartRate from stored summary`() =
        runTest {
            val today = LocalDate.now()
            val zoneId = ZoneId.systemDefault()
            val todayMs = today.atStartOfDay(zoneId).toInstant().toEpochMilli()

            val existingSummary =
                DailySummaryEntity(
                    dateMidnightMs = todayMs,
                    rhrBpm = 52f,
                    restingHeartRate = 48,
                    baselineCalculatedAtDate = today,
                )
            coEvery { dailySummaryDao.getByDate(todayMs) } returns existingSummary

            // Ensure use case returns success
            coEvery {
                computeSleepMetricsUseCase(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns
                com.gregor.lauritz.healthdashboard.domain.model.Result
                    .success(existingSummary)

            val result = repo.computeDailySummary(today)

            kotlin.test.assertEquals(52f, result.rhrBpm, "RHR baseline should be loaded from rhrBpm")
        }

    @Test
    fun `scoring path with session passes restingHrRatio from computeSleepMetrics result`() =
        runTest {
            val today = LocalDate.now()
            val zoneId = ZoneId.systemDefault()

            val mockSession =
                com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity(
                    id = "test_session",
                    startTime = today.atStartOfDay(zoneId).toInstant().toEpochMilli() - 8 * 3600000,
                    endTime = today.atStartOfDay(zoneId).toInstant().toEpochMilli() + 1800000,
                    durationMinutes = 450,
                    efficiency = 85f,
                    deepSleepMinutes = 90,
                    remSleepMinutes = 90,
                    lightSleepMinutes = 210,
                    awakeMinutes = 15,
                )
            coEvery { sleepSessionDao.getSessionEndingInRange(any(), any()) } returns mockSession
            coEvery { sleepSessionDao.countSince(any()) } returns 7

            coEvery { baselineComputer.computeHrvBaselineBetween(any(), any(), any()) } returns 45
            coEvery {
                computeSleepMetricsUseCase(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(),
                )
            } returns
                com.gregor.lauritz.healthdashboard.domain.model.Result
                    .success(DailySummaryEntity(0L, restingHeartRate = 48, restingHrRatio = 0.96f))

            val result = repo.computeDailySummary(today)

            kotlin.test.assertEquals(48, result.restingHeartRate)
            kotlin.test.assertEquals(0.96f, result.restingHrRatio)
        }

    @Test
    fun `scoring path without session leaves restingHeartRate null`() =
        runTest {
            val today = LocalDate.now()

            coEvery { sleepSessionDao.getSessionEndingInRange(any(), any()) } returns null
            coEvery { sleepSessionDao.countSince(any()) } returns 7

            coEvery { baselineComputer.computeHrvBaselineBetween(any(), any(), any()) } returns 45
            coEvery {
                computeSleepMetricsUseCase(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(),
                )
            } returns
                com.gregor.lauritz.healthdashboard.domain.model.Result
                    .success(DailySummaryEntity(0L))

            val result = repo.computeDailySummary(today)

            kotlin.test.assertNull(result.restingHeartRate)
            kotlin.test.assertNull(result.restingHrRatio)
        }

    @Test
    fun `wakeHrCollector with null fields does not crash`() =
        runTest {
            val today = LocalDate.now()
            val zoneId = ZoneId.systemDefault()

            val mockSession =
                com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity(
                    id = "test_session",
                    startTime = today.atStartOfDay(zoneId).toInstant().toEpochMilli() - 8 * 3600000,
                    endTime = today.atStartOfDay(zoneId).toInstant().toEpochMilli() + 1800000,
                    durationMinutes = 450,
                    efficiency = 85f,
                    deepSleepMinutes = 90,
                    remSleepMinutes = 90,
                    lightSleepMinutes = 210,
                    awakeMinutes = 15,
                )
            coEvery { sleepSessionDao.getSessionEndingInRange(any(), any()) } returns mockSession
            coEvery { sleepSessionDao.countSince(any()) } returns 7

            val nullWakeHrResult =
                SleepPercentileRhrCalculator.SleepPercentileRhrResult(
                    currentRestingHr = null,
                    restingHrBaseline = null,
                    restingHrRatio = null,
                )
            coEvery { sleepPercentileRhrCalculator.collect(any(), any(), any()) } returns nullWakeHrResult

            coEvery { baselineComputer.computeHrvBaselineBetween(any(), any(), any()) } returns 45
            coEvery {
                computeSleepMetricsUseCase(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(),
                )
            } returns
                com.gregor.lauritz.healthdashboard.domain.model.Result
                    .success(DailySummaryEntity(0L))

            // Should not throw
            val result = repo.computeDailySummary(today)
            kotlin.test.assertNull(result.restingHeartRate)
        }
}
