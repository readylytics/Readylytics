package app.readylytics.health.domain.scoring

import app.readylytics.health.data.local.dao.DailySummaryDao
import app.readylytics.health.data.local.dao.HeartRateDao
import app.readylytics.health.data.local.dao.HrvDao
import app.readylytics.health.data.local.dao.SleepHrSample
import app.readylytics.health.data.local.dao.SleepSessionDao
import app.readylytics.health.data.local.entity.DailySummaryEntity
import app.readylytics.health.data.local.entity.SleepSessionEntity
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
 * Equivalence oracle for [BaselineComputer.computeBackfillBaselines].
 *
 * The historical-baseline backfill was changed from ~11 DB queries per day to a fixed, batched set
 * of reads. This test proves the batched path produces **byte-identical** results to the original
 * per-day methods ([BaselineComputer.computeHrvWindowsBetween] excluding the day's own session, and
 * [BaselineComputer.computeAdaptiveBaselineRhrBpmBetween]) by running both independent
 * implementations over the same in-memory-faked DAOs and asserting equality across a realistic,
 * varied dataset (invalid nights, missing HRV, sparse HR coverage, partial early windows).
 */
class BaselineComputerBackfillEquivalenceTest {
    private val heartRateDao = mockk<HeartRateDao>()
    private val hrvDao = mockk<HrvDao>()
    private val sleepSessionDao = mockk<SleepSessionDao>()
    private val scoringCalculator = mockk<ScoringCalculator>()
    private val dailySummaryDao = mockk<DailySummaryDao>()

    private val baselineComputer =
        BaselineComputer(heartRateDao, hrvDao, sleepSessionDao, scoringCalculator, dailySummaryDao)

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
        // 60 nights, one per day, each fully contained within its own day (start 00:30, end 06:30).
        val halfHour = 30 * 60 * 1000L
        val sixHours = 6 * 60 * 60 * 1000L
        for (i in 0 until 60) {
            val id = "s$i"
            val start = dayStartMs(i) + halfHour
            val end = dayStartMs(i) + halfHour + sixHours
            // Vary the data to exercise validity/coverage branches.
            val durationMinutes = if (i % 11 == 0) 120 else 360 // some too-short → durationValid=false
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
                // most nights have HRV; (i%7==3) nights have none → hrvMean null
                rmssdById[id] = listOf(40f + i % 5, 45f + i % 3, 50f + i % 4)
            }
            if (i % 9 != 4) {
                // most nights have an avg HR; (i%9==4) nights missing → rhrValid=false
                avgHrById[id] = 52 + (i % 10)
            }
            hrProjectionById[id] =
                if (i % 13 == 0) {
                    listOf(70, 66, 61) // <10 samples → no nadir
                } else {
                    (0 until 12).map { 48 + i % 7 + it } // >=10 samples (unsorted ok; fake sorts)
                }
        }

        // Fake DAO semantics mirroring the SQL queries the per-day methods rely on.
        coEvery { sleepSessionDao.getBetween(any(), any()) } answers {
            val from = firstArg<Long>()
            val to = secondArg<Long>()
            sessions.filter { it.startTime >= from && it.endTime <= to }.sortedBy { it.startTime }
        }
        coEvery { sleepSessionDao.getSessionEndingInRange(any(), any()) } answers {
            val from = firstArg<Long>()
            val to = secondArg<Long>()
            sessions.filter { it.endTime in from until to }.minByOrNull { it.endTime }
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
            // Real query: ORDER BY sessionId, beatsPerMinute ASC. Per-session ascending sort is what
            // the percentile index depends on; both paths groupBy sessionId.
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
    fun `batched backfill equals per-day methods across a varied 60-day history`() =
        runTest {
            // Mix of partial early windows, mid-range, and near-end days.
            val dayIndices = listOf(0, 1, 3, 7, 8, 15, 30, 40, 56, 59)
            val summaries = dayIndices.map { DailySummaryEntity(dateMidnightMs = dayStartMs(it)) }

            val batched = baselineComputer.computeBackfillBaselines(summaries, percentile)

            for (summary in summaries) {
                val dayMidnightMs = summary.dateMidnightMs
                val nextDayMidnightMs =
                    java.time.Instant
                        .ofEpochMilli(dayMidnightMs)
                        .plus(1, java.time.temporal.ChronoUnit.DAYS)
                        .toEpochMilli()

                // Reproduce exactly what ComputeHistoricalBaselinesUseCase did per day.
                val ownSession = sleepSessionDao.getSessionEndingInRange(dayMidnightMs, nextDayMidnightMs)
                val expectedWindows =
                    baselineComputer.computeHrvWindowsBetween(dayMidnightMs, nextDayMidnightMs, ownSession?.id)
                val expectedRhr =
                    baselineComputer.computeAdaptiveBaselineRhrBpmBetween(dayMidnightMs, nextDayMidnightMs, percentile)
                val expectedRhrHistory =
                    baselineComputer.rhrHistoryBetween(dayMidnightMs, nextDayMidnightMs, percentile)

                val actual = batched[dayMidnightMs]
                requireNotNull(actual) { "missing batched result for day $dayMidnightMs" }

                assertEquals(
                    expectedWindows?.muHistory ?: emptyList(),
                    actual.muHistory,
                    "muHistory mismatch for day $dayMidnightMs",
                )
                assertEquals(
                    expectedWindows?.sigmaHistory ?: emptyList(),
                    actual.sigmaHistory,
                    "sigmaHistory mismatch for day $dayMidnightMs",
                )
                assertEquals(expectedRhr, actual.rhrBpm, "rhrBpm mismatch for day $dayMidnightMs")
                assertEquals(expectedRhrHistory, actual.rhrHistory, "rhrHistory mismatch for day $dayMidnightMs")
            }
        }

    @Test
    fun `empty history yields default rhr and empty windows for every requested day`() =
        runTest {
            sessions.clear()
            val summaries = listOf(0, 5, 10).map { DailySummaryEntity(dateMidnightMs = dayStartMs(it)) }

            val batched = baselineComputer.computeBackfillBaselines(summaries, percentile)

            assertEquals(3, batched.size)
            batched.values.forEach {
                assertEquals(emptyList(), it.muHistory)
                assertEquals(emptyList(), it.sigmaHistory)
                assertEquals(ScoringConstants.DEFAULT_RHR_BPM, it.rhrBpm)
                assertEquals(emptyList(), it.rhrHistory)
            }
        }
}
