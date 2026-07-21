package app.readylytics.health.data.repository

import app.readylytics.health.data.local.dao.BloodPressureRecordDao
import app.readylytics.health.data.local.dao.BodyFatRecordDao
import app.readylytics.health.data.local.dao.DailySummaryDao
import app.readylytics.health.data.local.dao.HeartRateDao
import app.readylytics.health.data.local.dao.OxygenSaturationRecordDao
import app.readylytics.health.data.local.dao.SleepSessionDao
import app.readylytics.health.data.local.dao.WeightRecordDao
import app.readylytics.health.data.local.dao.WorkoutDao
import app.readylytics.health.data.local.entity.DailySummaryEntity
import app.readylytics.health.data.local.entity.SleepSessionEntity
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.domain.model.Result
import app.readylytics.health.domain.repository.ScoringHistoryRepository
import app.readylytics.health.domain.scoring.BaselineComputer
import app.readylytics.health.domain.scoring.ComputeSleepMetricsUseCase
import app.readylytics.health.domain.scoring.ComputeWorkoutTrimpUseCase
import app.readylytics.health.domain.scoring.ScoringCalculator
import app.readylytics.health.domain.scoring.ScoringConfigFactory
import app.readylytics.health.domain.scoring.sleep.SleepPercentileRhrCalculator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals

class ScoringRepositoryBiphasicIntegrationTest {
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

    private val repo =
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
            UnconfinedTestDispatcher(),
        )

    @Test
    fun `biphasic scoring aggregates sleep totals but keeps recovery inputs on core cluster`() =
        runTest {
            val zoneId = ZoneId.of("Europe/Berlin")
            val targetDate = LocalDate.of(2026, 7, 9)
            val dayMidnightMs = targetDate.atStartOfDay(zoneId).toInstant().toEpochMilli()

            every { settingsRepo.userPreferences } returns flowOf(UserPreferences())
            coEvery { scoringHistoryRepository.getDailySummaryByDate(any()) } returns null
            coEvery { baselineComputer.computeAdaptiveBaselineRhrBpmBetween(any(), any(), any(), any()) } returns 60f
            coEvery { baselineComputer.computeHrvWindowsBetween(any(), any(), any(), any()) } returns
                BaselineComputer.HrvWindows(
                    muHistory = emptyList(),
                    sigmaHistory = emptyList(),
                    historicalSessions = emptyList(),
                    validHistoricalSessionIds = emptyList(),
                    validHistoricalDayCount = 6,
                )
            coEvery { sleepSessionDao.countSince(any()) } returns 10
            coEvery { sleepSessionDao.getOverlapping(any(), any()) } returns
                listOf(
                    sleepSession(
                        id = "core-1",
                        start =
                            LocalDate
                                .of(2026, 7, 8)
                                .atTime(23, 0)
                                .atZone(zoneId)
                                .toInstant()
                                .toEpochMilli(),
                        end =
                            LocalDate
                                .of(2026, 7, 9)
                                .atTime(2, 30)
                                .atZone(zoneId)
                                .toInstant()
                                .toEpochMilli(),
                        durationMinutes = 210,
                        light = 90,
                        deep = 60,
                        rem = 60,
                    ),
                    sleepSession(
                        id = "core-2",
                        start =
                            LocalDate
                                .of(2026, 7, 9)
                                .atTime(3, 15)
                                .atZone(zoneId)
                                .toInstant()
                                .toEpochMilli(),
                        end =
                            LocalDate
                                .of(2026, 7, 9)
                                .atTime(7, 15)
                                .atZone(zoneId)
                                .toInstant()
                                .toEpochMilli(),
                        durationMinutes = 240,
                        light = 150,
                        deep = 30,
                        rem = 45,
                        awake = 15,
                    ),
                    sleepSession(
                        id = "nap-pass",
                        start =
                            LocalDate
                                .of(2026, 7, 9)
                                .atTime(13, 0)
                                .atZone(zoneId)
                                .toInstant()
                                .toEpochMilli(),
                        end =
                            LocalDate
                                .of(2026, 7, 9)
                                .atTime(14, 0)
                                .atZone(zoneId)
                                .toInstant()
                                .toEpochMilli(),
                        durationMinutes = 60,
                        light = 30,
                        deep = 12,
                        rem = 3,
                    ),
                )
            coEvery { dailySummaryDao.getByDates(any()) } returns emptyList()
            coEvery { oxygenSaturationRecordDao.getByTimeRange(any(), any()) } returns emptyList()
            coEvery { heartRateDao.getByTimeRange(any(), any()) } returns emptyList()
            coEvery { workoutDao.getWorkoutsInRange(any(), any()) } returns emptyList()

            val sessionSlot = slot<SleepSessionEntity>()
            coEvery {
                computeSleepMetricsUseCase(
                    capture(sessionSlot),
                    any(),
                    eq(targetDate),
                    any(),
                    any(),
                    any(),
                    anyNullable<Float>(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } answers {
                val scoringSession = sessionSlot.captured
                Result.success(
                    DailySummaryEntity(
                        dateMidnightMs = dayMidnightMs,
                        sleepDurationMinutes = scoringSession.durationMinutes,
                        deepSleepPercent =
                            scoringSession.deepSleepMinutes / scoringSession.durationMinutes.toFloat() * 100f,
                        remSleepPercent =
                            scoringSession.remSleepMinutes / scoringSession.durationMinutes.toFloat() * 100f,
                    ),
                )
            }

            val result = repo.computeDailySummary(targetDate)

            assertEquals(510, result.sleepDurationMinutes)
            assertEquals(510, sessionSlot.captured.durationMinutes)
            assertEquals(270, sessionSlot.captured.lightSleepMinutes)
            assertEquals(102, sessionSlot.captured.deepSleepMinutes)
            assertEquals(108, sessionSlot.captured.remSleepMinutes)
            assertEquals(15, sessionSlot.captured.awakeMinutes)
            assertEquals(
                LocalDate
                    .of(2026, 7, 8)
                    .atTime(23, 0)
                    .atZone(zoneId)
                    .toInstant()
                    .toEpochMilli(),
                sessionSlot.captured.startTime,
            )
            assertEquals(
                LocalDate
                    .of(2026, 7, 9)
                    .atTime(7, 15)
                    .atZone(zoneId)
                    .toInstant()
                    .toEpochMilli(),
                sessionSlot.captured.endTime,
            )
            coVerify {
                computeSleepMetricsUseCase(
                    any(),
                    any(),
                    eq(targetDate),
                    any(),
                    any(),
                    any(),
                    anyNullable<Float>(),
                    any(),
                    any(),
                    any(),
                    eq(setOf("core-1", "core-2")),
                )
            }
            coVerify(exactly = 0) { sleepSessionDao.getSessionEndingInRange(any(), any()) }
        }

    private fun sleepSession(
        id: String,
        start: Long,
        end: Long,
        durationMinutes: Int,
        light: Int,
        deep: Int,
        rem: Int,
        awake: Int = 0,
    ) = SleepSessionEntity(
        id = id,
        startTime = start,
        endTime = end,
        durationMinutes = durationMinutes,
        efficiency = 85f,
        deepSleepMinutes = deep,
        remSleepMinutes = rem,
        lightSleepMinutes = light,
        awakeMinutes = awake,
        deviceName = "device-$id",
    )
}
