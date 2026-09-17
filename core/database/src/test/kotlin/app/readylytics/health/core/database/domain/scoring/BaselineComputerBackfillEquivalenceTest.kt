package app.readylytics.health.core.database.domain.scoring

import app.readylytics.health.core.model.domain.scoring.ScoringConstants
import app.readylytics.health.core.scoring.domain.scoring.BaselineComputer
import app.readylytics.health.core.scoring.domain.scoring.ComputeHistoricalBaselinesUseCase
import app.readylytics.health.core.scoring.domain.scoring.ScoringCalculator

import app.readylytics.health.core.databaseschema.data.local.dao.DailySummaryDao
import app.readylytics.health.core.databaseschema.data.local.dao.HeartRateDao
import app.readylytics.health.core.databaseschema.data.local.dao.HrvDao
import app.readylytics.health.core.databaseschema.data.local.dao.MinuteBucketDao
import app.readylytics.health.core.databaseschema.data.local.dao.SleepHrSample
import app.readylytics.health.core.databaseschema.data.local.dao.SleepSessionDao
import app.readylytics.health.core.databaseschema.data.local.entity.DailySummaryEntity
import app.readylytics.health.core.databaseschema.data.local.entity.SleepSessionEntity
import app.readylytics.health.core.database.data.mapper.DailySummaryMapper
import app.readylytics.health.core.database.data.repository.ScoringHistoryRepositoryImpl
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
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
    private val minuteBucketDao = mockk<MinuteBucketDao>(relaxed = true)

    private val scoringHistoryRepository =
        ScoringHistoryRepositoryImpl(heartRateDao, hrvDao, sleepSessionDao, dailySummaryDao, minuteBucketDao)
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
        setupFixtureData()
        setupDaoMocks()
    }

    private fun setupFixtureData() {
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
    }

    private fun setupDaoMocks() {
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
        coEvery { heartRateDao.getVisibleSleepHrProjectionForSessions(any()) } answers {
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

    /**
     * Reproduces exactly what `ComputeHistoricalBaselinesUseCase` did per day and asserts the
     * batched result for [summary] agrees with the independent, per-day bounded selectors -- not a
     * second call to the batch helper -- so this proves the live and backfill RHR/HRV paths share
     * the same membership policy.
     */
    private suspend fun assertBackfillMatchesLiveSelectors(
        summary: DailySummaryEntity,
        batched: Map<LocalDate, BaselineComputer.BackfillBaseline>,
    ) {
        val dayMidnightMs = summary.dateMidnightMs
        val nextDayMidnightMs = Instant.ofEpochMilli(dayMidnightMs).plus(1, ChronoUnit.DAYS).toEpochMilli()

        val ownSession = sleepSessionDao.getSessionEndingInRange(dayMidnightMs, nextDayMidnightMs)
        val expectedWindows =
            baselineComputer.computeHrvWindowsBetween(
                fromMs = dayMidnightMs,
                toMs = nextDayMidnightMs,
                zoneId = zone,
                excludeSessionIds = ownSession?.id?.let(::setOf).orEmpty(),
            )
        val expectedRhr =
            baselineComputer.computeAdaptiveBaselineRhrBpmBetween(
                fromMs = dayMidnightMs,
                toMs = nextDayMidnightMs,
                percentile = percentile,
                zoneId = zone,
            )
        val expectedRhrHistory =
            baselineComputer.rhrHistoryBetween(
                fromMs = dayMidnightMs,
                toMs = nextDayMidnightMs,
                percentile = percentile,
                zoneId = zone,
            )
        val scoreDay = Instant.ofEpochMilli(dayMidnightMs).atZone(zone).toLocalDate()
        val actual = batched[scoreDay]
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

    @Test
    fun `batched backfill equals per-day methods across a varied 60-day history`() =
        runTest {
            // Mix of partial early windows, mid-range, and near-end days. Day 30 sits exactly on
            // the BASELINE_DAYS boundary relative to day0; days 3/7/8 hit the sparse-HRV/sparse-RHR
            // branches of the fixture (i%7==3, i%9==4, i%11==0, i%13==0).
            val dayIndices = listOf(0, 1, 3, 7, 8, 15, 30, 40, 56, 59)
            val summaries = dayIndices.map { DailySummaryEntity(dateMidnightMs = dayStartMs(it)) }

            val batched =
                baselineComputer.computeBackfillBaselines(
                    summaries.map { DailySummaryMapper.toDomain(it, zone) },
                    percentile,
                    zoneId = zone,
                )

            for (summary in summaries) {
                assertBackfillMatchesLiveSelectors(summary, batched)
            }
        }

    /**
     * Appends [count] extreme, sharply-different future nights (day index [startIndexExclusive] +
     * 1 .. + [count]) to the shared fixture -- sessions that must never leak into an earlier day's
     * own RHR baseline just because a batch request also asked for one of these later days.
     */
    private fun addExtremeFutureNights(
        startIndexExclusive: Int,
        count: Int,
    ) {
        val hour = 60 * 60 * 1000L
        for (offset in 1..count) {
            val i = startIndexExclusive + offset
            val id = "future_$i"
            sessions +=
                SleepSessionEntity(
                    id = id,
                    startTime = dayStartMs(i) + hour,
                    endTime = dayStartMs(i) + 7 * hour,
                    durationMinutes = 360,
                    efficiency = 95f,
                    deepSleepMinutes = 80,
                    remSleepMinutes = 90,
                    lightSleepMinutes = 180,
                    awakeMinutes = 10,
                )
            rmssdById[id] = listOf(15f, 16f, 17f)
            avgHrById[id] = 150
            hrProjectionById[id] = (0 until 12).map { 140 + it }
        }
    }

    @Test
    fun `RHR baseline for day D is unaffected by sharply different nights added later in the same batch`() =
        runTest {
            val dIndex = 40
            val dDate = day0.plusDays(dIndex.toLong())
            val dSummary = DailySummaryEntity(dateMidnightMs = dayStartMs(dIndex))

            // Extreme future nights (D+1..D+20) with wildly different HR/HRV data that must never
            // leak into D's own baseline no matter what else the caller includes in the same batch.
            addExtremeFutureNights(dIndex, 20)
            val futureSummary = DailySummaryEntity(dateMidnightMs = dayStartMs(dIndex + 20))

            val alone =
                baselineComputer.computeBackfillBaselines(
                    listOf(DailySummaryMapper.toDomain(dSummary, zone)),
                    percentile,
                    zoneId = zone,
                )
            val withFuture =
                baselineComputer.computeBackfillBaselines(
                    listOf(
                        DailySummaryMapper.toDomain(dSummary, zone),
                        DailySummaryMapper.toDomain(futureSummary, zone),
                    ),
                    percentile,
                    zoneId = zone,
                )
            val expectedRhr =
                baselineComputer.computeAdaptiveBaselineRhrBpmBetween(
                    fromMs = dayStartMs(dIndex),
                    toMs = dayStartMs(dIndex + 1),
                    percentile = percentile,
                    zoneId = zone,
                )
            val expectedHistory =
                baselineComputer.rhrHistoryBetween(
                    fromMs = dayStartMs(dIndex),
                    toMs = dayStartMs(dIndex + 1),
                    percentile = percentile,
                    zoneId = zone,
                )

            assertEquals(alone[dDate]?.rhrBpm, withFuture[dDate]?.rhrBpm, "future nights leaked into D's rhrBpm")
            assertEquals(
                alone[dDate]?.rhrHistory,
                withFuture[dDate]?.rhrHistory,
                "future nights leaked into D's rhrHistory",
            )
            assertEquals(expectedRhr, withFuture[dDate]?.rhrBpm, "backfill and live selectors disagree for D")
            assertEquals(
                expectedHistory,
                withFuture[dDate]?.rhrHistory,
                "backfill and live rhrHistory disagree for D",
            )
        }

    @Test
    fun `RHR baseline across a scoring-zone DST transition is unaffected by future nights in the batch`() =
        runTest {
            val berlin = ZoneId.of("Europe/Berlin")
            // 2026-03-29 is Germany's spring-forward DST transition (02:00 -> 03:00 local).
            val dDate = LocalDate.of(2026, 4, 5)
            fun startMs(date: LocalDate) = date.atStartOfDay(berlin).toInstant().toEpochMilli()

            sessions.clear()
            rmssdById.clear()
            avgHrById.clear()
            hrProjectionById.clear()

            val hour = 60 * 60 * 1000L
            fun addNight(
                date: LocalDate,
                id: String,
                hrvSamples: List<Float>,
                avgHr: Int,
                hrSamples: List<Int>,
            ) {
                sessions +=
                    SleepSessionEntity(
                        id = id,
                        startTime = startMs(date) + hour,
                        endTime = startMs(date) + 7 * hour,
                        durationMinutes = 360,
                        efficiency = 92f,
                        deepSleepMinutes = 80,
                        remSleepMinutes = 90,
                        lightSleepMinutes = 180,
                        awakeMinutes = 10,
                    )
                rmssdById[id] = hrvSamples
                avgHrById[id] = avgHr
                hrProjectionById[id] = hrSamples
            }

            var day = dDate.minusDays(ScoringConstants.BASELINE_DAYS)
            while (!day.isAfter(dDate)) {
                addNight(day, "berlin_$day", listOf(42f, 44f, 46f), 55, (0 until 12).map { 48 + it })
                day = day.plusDays(1)
            }
            // Extreme future nights that must never leak into D's own baseline. Outnumbering the
            // ~31-night lookback population (not just adding a minority of outliers) is deliberate:
            // a median is robust to a minority of outliers, so a smaller future set could pass even
            // with the future-leak bug present.
            for (offset in 1..40) {
                val future = dDate.plusDays(offset.toLong())
                addNight(future, "berlinFuture_$future", listOf(15f, 16f, 17f), 150, (0 until 12).map { 140 + it })
            }

            val dSummary = DailySummaryEntity(dateMidnightMs = startMs(dDate))
            val futureSummary = DailySummaryEntity(dateMidnightMs = startMs(dDate.plusDays(40)))

            val alone =
                baselineComputer.computeBackfillBaselines(
                    listOf(DailySummaryMapper.toDomain(dSummary, berlin)),
                    percentile,
                    zoneId = berlin,
                )
            val withFuture =
                baselineComputer.computeBackfillBaselines(
                    listOf(
                        DailySummaryMapper.toDomain(dSummary, berlin),
                        DailySummaryMapper.toDomain(futureSummary, berlin),
                    ),
                    percentile,
                    zoneId = berlin,
                )
            val liveRhr =
                baselineComputer.computeAdaptiveBaselineRhrBpmBetween(
                    fromMs = startMs(dDate),
                    toMs = startMs(dDate.plusDays(1)),
                    percentile = percentile,
                    zoneId = berlin,
                )

            assertEquals(
                alone[dDate]?.rhrBpm,
                withFuture[dDate]?.rhrBpm,
                "future nights leaked across the DST-spanning batch",
            )
            assertEquals(
                liveRhr,
                withFuture[dDate]?.rhrBpm,
                "backfill/live RHR disagree across the DST transition",
            )
        }

    @Test
    fun `empty history yields default rhr and empty windows for every requested day`() =
        runTest {
            sessions.clear()
            val summaries = listOf(0, 5, 10).map { DailySummaryEntity(dateMidnightMs = dayStartMs(it)) }

            val batched =
                baselineComputer.computeBackfillBaselines(
                    summaries.map { DailySummaryMapper.toDomain(it, zone) },
                    percentile,
                    zoneId = zone,
                )

            assertEquals(3, batched.size)
            batched.values.forEach {
                assertEquals(emptyList(), it.muHistory)
                assertEquals(emptyList(), it.sigmaHistory)
                assertEquals(ScoringConstants.DEFAULT_RHR_BPM, it.rhrBpm)
                assertEquals(emptyList(), it.rhrHistory)
            }
        }
}
