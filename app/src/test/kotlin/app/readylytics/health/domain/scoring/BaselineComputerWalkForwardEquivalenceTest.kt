package app.readylytics.health.domain.scoring

import app.readylytics.health.data.local.dao.DailySummaryDao
import app.readylytics.health.data.local.dao.HeartRateDao
import app.readylytics.health.data.local.dao.HrvDao
import app.readylytics.health.data.local.dao.SleepHrSample
import app.readylytics.health.data.local.dao.SleepSessionDao
import app.readylytics.health.data.local.entity.SleepSessionEntity
import app.readylytics.health.data.repository.ScoringHistoryRepositoryImpl
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals

/**
 * PERF-002/WP-22 equivalence oracle: proves the walk-forward's shared-in-memory-slice overloads
 * (`prefetchedSessions` parameter on [BaselineComputer.computeAdaptiveBaselineRhrBpmBetween]/
 * [BaselineComputer.computeHrvBaselineBetween]/[BaselineComputer.computeHrvWindowsBetween]) return
 * byte-identical results to the DB-querying overloads they replace in a walk-forward, over the same
 * varied dataset used by [BaselineComputerBackfillEquivalenceTest].
 */
class BaselineComputerWalkForwardEquivalenceTest {
    private val heartRateDao = mockk<HeartRateDao>()
    private val hrvDao = mockk<HrvDao>()
    private val sleepSessionDao = mockk<SleepSessionDao>()
    private val scoringCalculator = mockk<ScoringCalculator>()
    private val dailySummaryDao = mockk<DailySummaryDao>()

    private val scoringHistoryRepository =
        ScoringHistoryRepositoryImpl(heartRateDao, hrvDao, sleepSessionDao, dailySummaryDao)
    private val baselineComputer = BaselineComputer(scoringHistoryRepository, scoringCalculator)

    private val zone: ZoneId = ZoneId.systemDefault()
    private val day0: LocalDate = LocalDate.of(2026, 1, 1)
    private val percentile = 5

    private val sessions = mutableListOf<SleepSessionEntity>()
    private val rmssdById = mutableMapOf<String, List<Float>>()
    private val avgHrById = mutableMapOf<String, Int>()
    private val hrProjectionById = mutableMapOf<String, List<Int>>()

    private fun dayStartMs(i: Int): Long =
        day0
            .plusDays(i.toLong())
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()

    @Before
    fun setup() {
        val halfHour = 30 * 60 * 1000L
        val sixHours = 6 * 60 * 60 * 1000L
        for (i in 0 until 60) {
            val id = "s$i"
            val start = dayStartMs(i) + halfHour
            val end = dayStartMs(i) + halfHour + sixHours
            val durationMinutes = if (i % 11 == 0) 120 else 360
            sessions +=
                SleepSessionEntity(
                    id = id,
                    startTime = start,
                    endTime = end,
                    durationMinutes = durationMinutes,
                    efficiency = 95f,
                    deepSleepMinutes = 80,
                    remSleepMinutes = 90,
                    lightSleepMinutes = 180,
                    awakeMinutes = 10,
                )
            if (i % 7 != 3) {
                rmssdById[id] = listOf(40f + i % 5, 45f + i % 3, 50f + i % 4)
            }
            if (i % 9 != 4) {
                avgHrById[id] = 52 + (i % 10)
            }
            hrProjectionById[id] =
                if (i % 13 == 0) {
                    listOf(70, 66, 61)
                } else {
                    (0 until 12).map { 48 + i % 7 + it }
                }
        }

        coEvery { sleepSessionDao.getBetween(any(), any()) } answers {
            val from = firstArg<Long>()
            val to = secondArg<Long>()
            sessions.filter { it.startTime >= from && it.endTime <= to }.sortedBy { it.startTime }
        }
        coEvery { dailySummaryDao.getByDate(any()) } returns null
        coEvery { hrvDao.getSleepRmssdForSessionsMap(any()) } answers {
            val ids = firstArg<List<String>>()
            ids.mapNotNull { id -> rmssdById[id]?.let { id to it } }.toMap()
        }
        coEvery { heartRateDao.getAvgSleepHrForSessions(any()) } answers {
            val ids = firstArg<List<String>>()
            ids.mapNotNull { id -> avgHrById[id]?.let { id to it } }.toMap()
        }
        coEvery { heartRateDao.getSleepHrProjectionForSessions(any()) } answers {
            val ids = firstArg<List<String>>()
            ids.flatMap { id -> (hrProjectionById[id] ?: emptyList()).sorted().map { SleepHrSample(id, it) } }
        }
        every {
            scoringCalculator.validateNight(any(), any(), any(), any(), any(), any())
        } answers {
            val rmssdMs = firstArg<Float?>()
            val rhrBpm = secondArg<Float?>()
            val duration = thirdArg<Int>()
            val hrCoverageValid = arg<Boolean>(5)
            ScoringCalculator.NightValidationResult(
                rmssdValid = rmssdMs != null,
                rhrValid = rhrBpm != null,
                durationValid = duration >= 180,
                stagesValid = true,
                stagesSuspicious = false,
                hrCoverageValid = hrCoverageValid,
            )
        }
    }

    @Test
    fun `prefetched RHR baseline matches the per-day DB-querying overload across a varied history`() =
        runTest {
            val startDate = day0.plusDays(10)
            val endDate = day0.plusDays(40)
            val prefetched = baselineComputer.prefetchWalkForwardSessions(startDate, endDate, zone)

            for (dayOffset in listOf(10, 15, 22, 30, 40)) {
                val dayMidnightMs = dayStartMs(dayOffset)
                val nextDayMidnightMs = dayStartMs(dayOffset + 1)

                val expected =
                    baselineComputer.computeAdaptiveBaselineRhrBpmBetween(dayMidnightMs, nextDayMidnightMs, percentile)
                val actual =
                    baselineComputer.computeAdaptiveBaselineRhrBpmBetween(
                        fromMs = dayMidnightMs,
                        toMs = nextDayMidnightMs,
                        percentile = percentile,
                        prefetchedSessions = prefetched,
                    )

                assertEquals(expected, actual, "RHR baseline mismatch at day offset $dayOffset")
            }
        }

    @Test
    fun `prefetched HRV baseline matches the per-day DB-querying overload across a varied history`() =
        runTest {
            val startDate = day0.plusDays(10)
            val endDate = day0.plusDays(40)
            val prefetched = baselineComputer.prefetchWalkForwardSessions(startDate, endDate, zone)

            for (dayOffset in listOf(10, 15, 22, 30, 40)) {
                val dayMidnightMs = dayStartMs(dayOffset)
                val nextDayMidnightMs = dayStartMs(dayOffset + 1)

                val expected = baselineComputer.computeHrvBaselineBetween(dayMidnightMs, nextDayMidnightMs, null)
                val actual =
                    baselineComputer.computeHrvBaselineBetween(
                        fromMs = dayMidnightMs,
                        toMs = nextDayMidnightMs,
                        hrvBaselineOverride = null,
                        prefetchedSessions = prefetched,
                    )

                assertEquals(expected, actual, "HRV baseline mismatch at day offset $dayOffset")
            }
        }

    @Test
    fun `prefetched HRV windows match the per-day DB-querying overload across a varied history`() =
        runTest {
            val startDate = day0.plusDays(10)
            val endDate = day0.plusDays(40)
            val prefetched = baselineComputer.prefetchWalkForwardSessions(startDate, endDate, zone)

            for (dayOffset in listOf(10, 15, 22, 30, 40)) {
                val dayMidnightMs = dayStartMs(dayOffset)
                val nextDayMidnightMs = dayStartMs(dayOffset + 1)

                val expected = baselineComputer.computeHrvWindowsBetween(dayMidnightMs, nextDayMidnightMs)
                val actual =
                    baselineComputer.computeHrvWindowsBetween(
                        fromMs = dayMidnightMs,
                        toMs = nextDayMidnightMs,
                        prefetchedSessions = prefetched,
                    )

                assertEquals(expected?.muHistory, actual?.muHistory, "muHistory mismatch at day offset $dayOffset")
                assertEquals(
                    expected?.sigmaHistory,
                    actual?.sigmaHistory,
                    "sigmaHistory mismatch at day offset $dayOffset",
                )
                assertEquals(
                    expected?.validHistoricalDayCount,
                    actual?.validHistoricalDayCount,
                    "validHistoricalDayCount mismatch at day offset $dayOffset",
                )
            }
        }
}
