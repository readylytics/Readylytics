package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.databaseschema.data.local.dao.BloodPressureRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.BodyFatRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.BodyTemperatureRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.DailySummaryDao
import app.readylytics.health.core.databaseschema.data.local.dao.HeartRateDao
import app.readylytics.health.core.databaseschema.data.local.dao.MinuteBucketDao
import app.readylytics.health.core.databaseschema.data.local.dao.OxygenSaturationRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.SleepSessionDao
import app.readylytics.health.core.databaseschema.data.local.dao.WeightRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.WorkoutDao
import app.readylytics.health.core.databaseschema.data.local.entity.SleepSessionEntity
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.ScoringHistoryRepository
import app.readylytics.health.core.scoring.domain.scoring.AssembleDailySummaryUseCase
import app.readylytics.health.core.scoring.domain.scoring.BaselineComputer
import app.readylytics.health.core.scoring.domain.scoring.BuildLoadSeriesUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeSleepMetricsUseCase
import app.readylytics.health.core.scoring.domain.scoring.ResolveDailyBaselinesUseCase
import app.readylytics.health.core.scoring.domain.scoring.ScoringCalculator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * WP-14/C4 (WP-14): proves [ReadinessSummaryCoordinator.resolveSleepAggregation] builds
 * [SleepAggregationContext.coreRecoveryInput] strictly from the core cluster -- never a same-day
 * or later nap -- for both "this night's core" and "the nearest prior core" offset evidence, and
 * that the previous-core lookup needs nothing from the baseline/frozen-replay machinery
 * ([ComputeSleepMetricsUseCase]/[BaselineComputer] are never even invoked by this function).
 */
class BiphasicRecoveryScopeTest {
    private val workoutDao = mockk<WorkoutDao>(relaxed = true)
    private val sleepSessionDao = mockk<SleepSessionDao>(relaxed = true)
    private val dailySummaryDao = mockk<DailySummaryDao>(relaxed = true)
    private val heartRateDao = mockk<HeartRateDao>(relaxed = true)
    private val minuteBucketDao = mockk<MinuteBucketDao>(relaxed = true)
    private val weightRecordDao = mockk<WeightRecordDao>(relaxed = true)
    private val bodyFatRecordDao = mockk<BodyFatRecordDao>(relaxed = true)
    private val bloodPressureRecordDao = mockk<BloodPressureRecordDao>(relaxed = true)
    private val oxygenSaturationRecordDao = mockk<OxygenSaturationRecordDao>(relaxed = true)
    private val bodyTemperatureRecordDao = mockk<BodyTemperatureRecordDao>(relaxed = true)
    private val scoringHistoryRepository = mockk<ScoringHistoryRepository>(relaxed = true)
    private val baselineComputer = mockk<BaselineComputer>(relaxed = true)
    private val scoringCalculator = mockk<ScoringCalculator>(relaxed = true)
    private val computeSleepMetricsUseCase = mockk<ComputeSleepMetricsUseCase>(relaxed = true)

    private val dataLoader =
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
    private val seriesLoader = ScoringSeriesLoader(workoutDao, dailySummaryDao)

    private val coordinator =
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

    private val zoneId = ZoneId.of("Europe/Berlin")
    private val targetDate = LocalDate.of(2026, 7, 9)

    @Test
    fun `previous-core offset comes from yesterday's overnight core, not its later nap`() =
        runTest {
            val coreYesterday =
                sleepSession(
                    id = "core-yesterday",
                    start = LocalDate.of(2026, 7, 7).atTime(22, 0),
                    end = LocalDate.of(2026, 7, 8).atTime(6, 0),
                    endZoneOffsetSeconds = 3_600,
                )
            val napYesterdayAfternoon =
                sleepSession(
                    id = "nap-yesterday-afternoon",
                    start = LocalDate.of(2026, 7, 8).atTime(13, 0),
                    end = LocalDate.of(2026, 7, 8).atTime(14, 0),
                    durationMinutes = 60,
                    // Deliberately a different offset from the real previous core -- if this were
                    // ever picked up (the pre-C4 "most recent session" bug), the test below would
                    // see 0 instead of 3_600 and fail.
                    endZoneOffsetSeconds = 0,
                )
            val coreToday =
                sleepSession(
                    id = "core-today",
                    start = LocalDate.of(2026, 7, 8).atTime(23, 0),
                    end = LocalDate.of(2026, 7, 9).atTime(7, 0),
                    endZoneOffsetSeconds = 3_600,
                )
            coEvery { sleepSessionDao.getOverlapping(any(), any()) } returns
                listOf(coreYesterday, napYesterdayAfternoon, coreToday)

            val result = coordinator.resolveSleepAggregation(targetDate, zoneId, UserPreferences())

            requireNotNull(result)
            assertEquals(setOf("core-today"), result.coreRecoveryInput.sessionIds)
            assertEquals(3_600, result.coreRecoveryInput.previousCoreEndZoneOffsetSeconds)

            // The previous-core lookup lives entirely inside sleep-day aggregation -- it must not
            // reach into the baseline/frozen-replay machinery (which on a frozen day short-circuits
            // its own history window to empty; see ComputeSleepMetricsUseCase.resolveBaselineWindow).
            coVerify(exactly = 0) {
                baselineComputer.computeHrvWindowsBetween(any(), any(), any(), any(), any(), any())
            }
            coVerify(exactly = 0) { computeSleepMetricsUseCase(any()) }
        }

    @Test
    fun `a same-day nap never enters the core recovery input, but does grow total duration`() =
        runTest {
            val coreToday =
                sleepSession(
                    id = "core-today",
                    start = LocalDate.of(2026, 7, 8).atTime(23, 0),
                    end = LocalDate.of(2026, 7, 9).atTime(7, 0),
                )
            val napToday =
                sleepSession(
                    id = "nap-today",
                    start = LocalDate.of(2026, 7, 9).atTime(13, 0),
                    end = LocalDate.of(2026, 7, 9).atTime(14, 0),
                    durationMinutes = 60,
                )

            coEvery { sleepSessionDao.getOverlapping(any(), any()) } returns listOf(coreToday)
            val withoutNap = requireNotNull(coordinator.resolveSleepAggregation(targetDate, zoneId, UserPreferences()))

            coEvery { sleepSessionDao.getOverlapping(any(), any()) } returns listOf(coreToday, napToday)
            val withNap = requireNotNull(coordinator.resolveSleepAggregation(targetDate, zoneId, UserPreferences()))

            assertEquals(setOf("core-today"), withoutNap.coreRecoveryInput.sessionIds)
            assertEquals(setOf("core-today"), withNap.coreRecoveryInput.sessionIds)
            assertEquals(withoutNap.coreRecoveryInput.window, withNap.coreRecoveryInput.window)
            assertEquals(withoutNap.aggregate.totalDurationMinutes + 60, withNap.aggregate.totalDurationMinutes)
        }

    @Test
    fun `no prior session yields no previous-core offset`() =
        runTest {
            val coreToday =
                sleepSession(
                    id = "core-today",
                    start = LocalDate.of(2026, 7, 8).atTime(23, 0),
                    end = LocalDate.of(2026, 7, 9).atTime(7, 0),
                )
            coEvery { sleepSessionDao.getOverlapping(any(), any()) } returns listOf(coreToday)

            val result = requireNotNull(coordinator.resolveSleepAggregation(targetDate, zoneId, UserPreferences()))

            assertNull(result.coreRecoveryInput.previousCoreEndZoneOffsetSeconds)
        }

    private fun sleepSession(
        id: String,
        start: java.time.LocalDateTime,
        end: java.time.LocalDateTime,
        durationMinutes: Int = 480,
        endZoneOffsetSeconds: Int? = null,
    ): SleepSessionEntity =
        SleepSessionEntity(
            id = id,
            startTime = start.atZone(zoneId).toInstant().toEpochMilli(),
            endTime = end.atZone(zoneId).toInstant().toEpochMilli(),
            durationMinutes = durationMinutes,
            efficiency = 85f,
            deepSleepMinutes = 60,
            remSleepMinutes = 60,
            lightSleepMinutes = (durationMinutes - 120).coerceAtLeast(0),
            awakeMinutes = 0,
            deviceName = "device-$id",
            endZoneOffsetSeconds = endZoneOffsetSeconds,
        )
}
