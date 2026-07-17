package app.readylytics.health.data.repository

import app.readylytics.health.data.local.dao.*
import app.readylytics.health.data.local.entity.DailySummaryEntity
import app.readylytics.health.data.local.entity.SleepSessionEntity
import app.readylytics.health.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.domain.model.Result
import app.readylytics.health.domain.repository.ScoringHistoryRepository
import app.readylytics.health.domain.scoring.*
import app.readylytics.health.domain.scoring.sleep.SleepPercentileRhrCalculator
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
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
            )
        every { settingsRepo.userPreferences } returns flowOf(UserPreferences())
        coEvery { dailySummaryDao.getByDate(any()) } returns null
        coEvery { scoringHistoryRepository.getDailySummaryByDate(any()) } returns null
        coEvery { sleepSessionDao.getOverlapping(any(), any()) } returns emptyList()
        coEvery { sleepSessionDao.countSince(any()) } returns 10
        coEvery { baselineComputer.computeAdaptiveBaselineRhrBpmBetween(any(), any(), any(), any()) } returns 60f
        coEvery { baselineComputer.computeHrvWindowsBetween(any(), any(), any(), any()) } returns
            BaselineComputer.HrvWindows(
                muHistory = emptyList(),
                sigmaHistory = emptyList(),
                historicalSessions = emptyList(),
                validHistoricalSessionIds = emptyList(),
                validHistoricalDayCount = 6,
            )
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
                baselineComputer.computeAdaptiveBaselineRhrBpmBetween(todayMs, tomorrowMs, any(), any())
            } returns 55f

            // Mock RHR baseline for yesterday
            coEvery {
                baselineComputer.computeAdaptiveBaselineRhrBpmBetween(yesterdayMs, todayMs, any(), any())
            } returns 60f

            // Mock sleep sessions so the sleep metrics flow is exercised
            val mockSession =
                SleepSessionEntity(
                    id = "test_session",
                    startTime = yesterday.atStartOfDay(zoneId).toInstant().toEpochMilli() + 23 * 3600000L,
                    endTime = today.atStartOfDay(zoneId).toInstant().toEpochMilli() + 7 * 3600000L,
                    durationMinutes = 480,
                    efficiency = 90f,
                    deepSleepMinutes = 90,
                    remSleepMinutes = 90,
                    lightSleepMinutes = 240,
                    awakeMinutes = 60,
                )
            coEvery { sleepSessionDao.getSessionEndingInRange(any(), any()) } returns mockSession
            coEvery { sleepSessionDao.getOverlapping(any(), any()) } returns listOf(mockSession)

            // Ensure use case returns different hrvMuMssd values based on date
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
                    any(),
                    any(),
                )
            } answers {
                when (thirdArg<LocalDate>()) {
                    today -> Result.success(DailySummaryEntity(todayMs, hrvMuMssd = 3.5f))
                    yesterday -> Result.success(DailySummaryEntity(yesterdayMs, hrvMuMssd = 4.0f))
                    else -> Result.success(DailySummaryEntity(0L))
                }
            }

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
            coEvery { scoringHistoryRepository.getDailySummaryByDate(todayMs) } returns existingSummary

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
                    any(),
                    any(),
                )
            } returns
                Result
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
                SleepSessionEntity(
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
            coEvery { sleepSessionDao.getOverlapping(any(), any()) } returns listOf(mockSession)
            coEvery { sleepSessionDao.countSince(any()) } returns 7

            coEvery { baselineComputer.computeHrvBaselineBetween(any(), any(), any()) } returns 45
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
                    any(),
                    any(),
                )
            } returns
                Result.success(DailySummaryEntity(0L, restingHeartRate = 48, restingHrRatio = 0.96f))

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
                    any(),
                    any(),
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
                Result.success(DailySummaryEntity(0L))

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
                SleepSessionEntity(
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
            coEvery { sleepPercentileRhrCalculator.collect(any(), any(), any(), any()) } returns nullWakeHrResult

            coEvery { baselineComputer.computeHrvBaselineBetween(any(), any(), any()) } returns 45
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
                    any(),
                )
            } returns
                Result
                    .success(DailySummaryEntity(0L))

            // Should not throw
            val result = repo.computeDailySummary(today)
            kotlin.test.assertNull(result.restingHeartRate)
        }

    @Test
    fun `computeAndPersistDailySummary persists using same scoring zone snapshot as computation`() =
        runTest {
            val zoneA = ZoneId.of("Pacific/Kiritimati")
            val zoneB = ZoneId.of("UTC")
            val targetDate = LocalDate.of(2026, 1, 2)
            val prefsFlow = MutableStateFlow(UserPreferences(scoringZoneId = zoneA.id))
            val entitySlot = slot<DailySummaryEntity>()

            every { settingsRepo.userPreferences } returns prefsFlow
            coEvery { dailySummaryDao.getByDate(any()) } returns null
            coEvery { scoringHistoryRepository.getDailySummaryByDate(any()) } returns null
            coEvery { sleepSessionDao.countSince(any()) } coAnswers {
                prefsFlow.value = UserPreferences(scoringZoneId = zoneB.id)
                10
            }
            coEvery { dailySummaryDao.upsert(capture(entitySlot)) } returns Unit

            repo.computeAndPersistDailySummary(targetDate)

            assertEquals(
                targetDate
                    .atStartOfDay(zoneA)
                    .toInstant()
                    .toEpochMilli(),
                entitySlot.captured.dateMidnightMs,
            )
        }

    @Test
    fun `computeDailySummary persists modelTrimp per workout using computeWorkoutTrimpUseCase result`() =
        runTest {
            // SCORE-001/WP-10: the user-selected-model TRIMP computed per workout must be written
            // back onto WorkoutRecordEntity.modelTrimp, not just summed into dailyTrimpRaw in memory.
            val today = LocalDate.now()
            val zoneId = ZoneId.systemDefault()
            val dayStart = today.atStartOfDay(zoneId).toInstant().toEpochMilli()

            val workout =
                WorkoutRecordEntity(
                    id = "w1",
                    startTime = dayStart + 3_600_000L,
                    endTime = dayStart + 5_400_000L,
                    exerciseType = "RUNNING",
                    durationMinutes = 30,
                    zone1Minutes = 5f,
                    zone2Minutes = 20f,
                    zone3Minutes = 5f,
                    zone4Minutes = 0f,
                    zone5Minutes = 0f,
                    trimp = 40f,
                    avgHr = 140f,
                    modelTrimp = null,
                )
            coEvery { workoutDao.getWorkoutsInRange(any(), any()) } returns listOf(workout)
            every {
                computeWorkoutTrimpUseCase.execute(any(), any(), any(), any(), any(), any(), any(), any())
            } returns Result.success(55f)
            coEvery {
                computeSleepMetricsUseCase(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns Result.success(DailySummaryEntity(0L))

            val workoutSlot = slot<List<WorkoutRecordEntity>>()
            coEvery { workoutDao.upsertAll(capture(workoutSlot)) } returns Unit

            repo.computeDailySummary(today)

            coVerify(exactly = 1) { workoutDao.upsertAll(any()) }
            assertEquals(1, workoutSlot.captured.size)
            assertEquals("w1", workoutSlot.captured.first().id)
            assertEquals(55f, workoutSlot.captured.first().modelTrimp)
        }

    @Test
    fun `computeDailySummary skips workoutDao upsertAll when no workout's modelTrimp changed`() =
        runTest {
            // A workout already carrying the freshly computed modelTrimp value shouldn't trigger a
            // redundant write on every single walk-forward day.
            val today = LocalDate.now()
            val zoneId = ZoneId.systemDefault()
            val dayStart = today.atStartOfDay(zoneId).toInstant().toEpochMilli()

            val workout =
                WorkoutRecordEntity(
                    id = "w1",
                    startTime = dayStart + 3_600_000L,
                    endTime = dayStart + 5_400_000L,
                    exerciseType = "RUNNING",
                    durationMinutes = 30,
                    zone1Minutes = 5f,
                    zone2Minutes = 20f,
                    zone3Minutes = 5f,
                    zone4Minutes = 0f,
                    zone5Minutes = 0f,
                    trimp = 40f,
                    avgHr = 140f,
                    modelTrimp = 55f,
                )
            coEvery { workoutDao.getWorkoutsInRange(any(), any()) } returns listOf(workout)
            every {
                computeWorkoutTrimpUseCase.execute(any(), any(), any(), any(), any(), any(), any(), any())
            } returns Result.success(55f)
            coEvery {
                computeSleepMetricsUseCase(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns Result.success(DailySummaryEntity(0L))

            repo.computeDailySummary(today)

            coVerify(exactly = 0) { workoutDao.upsertAll(any()) }
        }
}
