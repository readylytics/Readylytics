package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.SleepHrSample
import app.readylytics.health.core.model.domain.model.SleepSession
import app.readylytics.health.core.model.domain.repository.ScoringHistoryRepository
import app.readylytics.health.core.model.domain.scoring.ScoringConstants
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepDayPolicy
import app.readylytics.health.core.scoring.domain.util.weightedPercentile
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.round
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class BaselineComputerTest {
    private val repository = mockk<ScoringHistoryRepository>()
    private val scoringCalculator =
        mockk<ScoringCalculator>().apply {
            io.mockk.every {
                validateNight(any(), any(), any(), any(), any(), any())
            } returns ScoringCalculator.NightValidationResult(
                rmssdValid = true,
                rhrValid = true,
                durationValid = true,
                stagesValid = true,
                stagesSuspicious = false,
                hrCoverageValid = true,
            )
        }
    private val baselineComputer = BaselineComputer(repository, scoringCalculator)

    @Test
    fun `rhrHistory computes percentile matching weightedPercentile`() =
        runTest {
            val now = Instant.parse("2026-06-01T00:00:00Z")
            val session =
                SleepSession(
                    id = "s1",
                    startTime = now.minus(8, ChronoUnit.HOURS).toEpochMilli(),
                    endTime = now.toEpochMilli(),
                    durationMinutes = 480,
                    efficiency = 0.9f,
                    deepSleepMinutes = 90,
                    remSleepMinutes = 90,
                    lightSleepMinutes = 270,
                    awakeMinutes = 30,
                )
            // 10 samples from 50 to 95 step 5
            val samples = (50..95 step 5).map { SleepHrSample("s1", it) }
            val values = samples.map { it.beatsPerMinute }.toIntArray()
            val weights = IntArray(values.size) { 1 }

            coEvery { repository.getSleepSessionsBetween(any(), any()) } returns listOf(session)
            coEvery { repository.getSleepHrProjectionForSessions(listOf("s1")) } returns samples

            val p5 = baselineComputer.rhrHistory(now, percentile = 5)
            val p50 = baselineComputer.rhrHistory(now, percentile = 50)
            val p95 = baselineComputer.rhrHistory(now, percentile = 95)

            assertEquals(listOf(weightedPercentile(values, weights, 0.05)), p5)
            assertEquals(listOf(weightedPercentile(values, weights, 0.50)), p50)
            assertEquals(listOf(weightedPercentile(values, weights, 0.95)), p95)
        }

    @Test
    fun `rhrHistoryBetween computes percentile bit-identical to expanded samples`() =
        runTest {
            val fromMs = Instant.parse("2026-06-01T00:00:00Z").toEpochMilli()
            val toMs = Instant.parse("2026-06-02T00:00:00Z").toEpochMilli()
            val zone = ZoneId.of("UTC")
            val session =
                SleepSession(
                    id = "s2",
                    startTime = fromMs + 1000,
                    endTime = fromMs + 28800000,
                    durationMinutes = 480,
                    efficiency = 0.95f,
                    deepSleepMinutes = 100,
                    remSleepMinutes = 100,
                    lightSleepMinutes = 260,
                    awakeMinutes = 20,
                )

            val rawSamples = listOf(48, 50, 52, 55, 58, 60, 62, 65, 70, 75, 80)
            val hrSamples = rawSamples.map { SleepHrSample("s2", it) }

            coEvery { repository.getSleepSessionsBetween(any(), any()) } returns listOf(session)
            coEvery { repository.getSleepRmssdForSessionsMap(any()) } returns mapOf("s2" to listOf(45f))
            coEvery { repository.getSleepHrProjectionForSessions(listOf("s2")) } returns hrSamples

            val result = baselineComputer.rhrHistoryBetween(fromMs, toMs, percentile = 10, zoneId = zone)

            val expected = rawSamples.let { list ->
                val index = round(0.10 * (list.size - 1)).toInt()
                list[index]
            }
            assertEquals(listOf(expected), result)
        }

    @Test
    fun `computeBackfillBaselines computes RHR percentile accurately`() =
        runTest {
            val zone = ZoneId.of("UTC")
            val date = LocalDate.of(2026, 6, 1)
            val summary = DailySummary(date = date)
            val midnight = date.atStartOfDay(zone).toInstant()
            val session =
                SleepSession(
                    id = "s3",
                    startTime = midnight.minus(8, ChronoUnit.HOURS).toEpochMilli(),
                    endTime = midnight.toEpochMilli(),
                    durationMinutes = 480,
                    efficiency = 0.9f,
                    deepSleepMinutes = 90,
                    remSleepMinutes = 90,
                    lightSleepMinutes = 270,
                    awakeMinutes = 30,
                )
            val samples = listOf(50, 52, 54, 56, 58, 60, 62, 64, 66, 68).map { SleepHrSample("s3", it) }

            coEvery { repository.getSleepSessionsBetween(any(), any()) } returns listOf(session)
            coEvery { repository.getSleepRmssdForSessionsMap(any()) } returns mapOf("s3" to listOf(50f))
            coEvery { repository.getSleepHrProjectionForSessions(any()) } returns samples

            val baselines = baselineComputer.computeBackfillBaselines(listOf(summary), percentile = 10, zoneId = zone)
            val baseline = baselines[date]

            assertEquals(52f, baseline?.rhrBpm)
        }

    private data class CrossZoneSnapshot(
        val rhrBetween: Float?,
        val backfillRhr: Float?,
        val hrvMuHistory: List<Float>?,
    )

    private suspend fun computeCrossZoneSnapshot(
        fromMs: Long,
        toMs: Long,
        targetDate: LocalDate,
        scoringZone: ZoneId,
    ): CrossZoneSnapshot {
        val rhrBetween =
            baselineComputer.computeAdaptiveBaselineRhrBpmBetween(
                fromMs = fromMs,
                toMs = toMs,
                percentile = 10,
                zoneId = scoringZone,
            )
        val backfill =
            baselineComputer.computeBackfillBaselines(
                summaries = listOf(DailySummary(date = targetDate)),
                percentile = 10,
                zoneId = scoringZone,
            )
        val hrvWindows =
            baselineComputer.computeHrvWindowsBetween(
                fromMs = fromMs,
                toMs = toMs,
                zoneId = scoringZone,
            )
        return CrossZoneSnapshot(rhrBetween, backfill[targetDate]?.rhrBpm, hrvWindows?.muHistory)
    }

    @Test
    fun `cross-zone determinism - deviceZone UTC+13 and scoringZone UTC-8 produces identical baselines`() =
        runTest {
            val scoringZone = ZoneId.of("America/Los_Angeles")
            val targetDate = LocalDate.of(2026, 6, 1)
            val dayMidnight = targetDate.atStartOfDay(scoringZone).toInstant()
            val fromMs = dayMidnight.toEpochMilli()
            val toMs = dayMidnight.plus(1, ChronoUnit.DAYS).toEpochMilli()

            val session =
                SleepSession(
                    id = "s_cross",
                    startTime = dayMidnight.minus(8, ChronoUnit.HOURS).toEpochMilli(),
                    endTime = dayMidnight.toEpochMilli(),
                    durationMinutes = 480,
                    efficiency = 0.92f,
                    deepSleepMinutes = 90,
                    remSleepMinutes = 90,
                    lightSleepMinutes = 270,
                    awakeMinutes = 30,
                )
            val samples = (50..69).map { SleepHrSample("s_cross", it) }

            coEvery { repository.getDailySummaryByDate(any(), any()) } returns null
            coEvery { repository.getSleepSessionsBetween(any(), any()) } returns listOf(session)
            coEvery { repository.getSleepRmssdForSessionsMap(any()) } returns mapOf("s_cross" to listOf(52f))
            coEvery { repository.getSleepHrProjectionForSessions(any()) } returns samples

            val originalDefault = java.util.TimeZone.getDefault()
            try {
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Tongatapu"))
                val snapshotUtc13 = computeCrossZoneSnapshot(fromMs, toMs, targetDate, scoringZone)

                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"))
                val snapshotUtcMinus5 = computeCrossZoneSnapshot(fromMs, toMs, targetDate, scoringZone)

                assertEquals(snapshotUtc13, snapshotUtcMinus5)
            } finally {
                java.util.TimeZone.setDefault(originalDefault)
            }
        }

    @Test
    fun `computeAdaptiveBaselineRhrBpmBetween honors rhrBaselineOverride`() =
        runTest {
            val fromMs = Instant.parse("2026-06-01T00:00:00Z").toEpochMilli()
            val toMs = Instant.parse("2026-06-02T00:00:00Z").toEpochMilli()
            val result =
                baselineComputer.computeAdaptiveBaselineRhrBpmBetween(
                    fromMs = fromMs,
                    toMs = toMs,
                    percentile = 10,
                    zoneId = ZoneId.of("UTC"),
                    rhrBaselineOverride = 55.5f,
                )
            assertEquals(55.5f, result)
        }

    @Test
    fun `computeAdaptiveBaselineRhrBpmBetween falls back to DEFAULT_RHR_BPM when no eligible nights`() =
        runTest {
            val fromMs = Instant.parse("2026-06-01T00:00:00Z").toEpochMilli()
            val toMs = Instant.parse("2026-06-02T00:00:00Z").toEpochMilli()
            val zone = ZoneId.of("UTC")

            coEvery { repository.getDailySummaryByDate(any(), any()) } returns null
            coEvery { repository.getSleepSessionsBetween(any(), any()) } returns emptyList()

            val result =
                baselineComputer.computeAdaptiveBaselineRhrBpmBetween(
                    fromMs = fromMs,
                    toMs = toMs,
                    percentile = 10,
                    zoneId = zone,
                )
            assertEquals(ScoringConstants.DEFAULT_RHR_BPM, result)
        }

    @Test
    fun `computeAdaptiveBaselineRhrBpmBetween excludes nights failing validation and falls back`() =
        runTest {
            val customCalculator =
                mockk<ScoringCalculator>().apply {
                    io.mockk.every {
                        validateNight(any(), any(), any(), any(), any(), any())
                    } returns ScoringCalculator.NightValidationResult(
                        rmssdValid = true,
                        rhrValid = false,
                        durationValid = true,
                        stagesValid = true,
                        stagesSuspicious = false,
                        hrCoverageValid = true,
                    )
                }
            val computerWithCustomValidation = BaselineComputer(repository, customCalculator)
            val fromMs = Instant.parse("2026-06-03T00:00:00Z").toEpochMilli()
            val toMs = Instant.parse("2026-06-04T00:00:00Z").toEpochMilli()
            val session =
                createSession(
                    id = "s_invalid_rhr",
                    startIso = "2026-06-01T23:00:00Z",
                    endIso = "2026-06-02T07:00:00Z",
                )
            coEvery { repository.getDailySummaryByDate(fromMs, any()) } returns null
            coEvery { repository.getSleepSessionsBetween(any(), any()) } returns listOf(session)
            coEvery { repository.getSleepRmssdForSessionsMap(any()) } returns emptyMap()
            coEvery {
                repository.getSleepHrProjectionForSessions(any())
            } returns listOf(SleepHrSample("s_invalid_rhr", 60))

            val result =
                computerWithCustomValidation.computeAdaptiveBaselineRhrBpmBetween(
                    fromMs = fromMs,
                    toMs = toMs,
                    percentile = 10,
                    zoneId = ZoneId.of("UTC"),
                )
            assertEquals(ScoringConstants.DEFAULT_RHR_BPM, result)
        }

    @Test
    fun `computeHrvBaselineBetween honors hrvBaselineOverride`() =
        runTest {
            val fromMs = Instant.parse("2026-06-01T00:00:00Z").toEpochMilli()
            val toMs = Instant.parse("2026-06-02T00:00:00Z").toEpochMilli()
            val result =
                baselineComputer.computeHrvBaselineBetween(
                    fromMs = fromMs,
                    toMs = toMs,
                    hrvBaselineOverride = 65.4f,
                    zoneId = ZoneId.of("UTC"),
                )
            assertEquals(65, result)
        }

    @Test
    fun `computeHrvBaselineBetween returns null when no eligible nights`() =
        runTest {
            val fromMs = Instant.parse("2026-06-01T00:00:00Z").toEpochMilli()
            val toMs = Instant.parse("2026-06-02T00:00:00Z").toEpochMilli()
            val zone = ZoneId.of("UTC")

            coEvery { repository.getDailySummaryByDate(any(), any()) } returns null
            coEvery { repository.getSleepSessionsBetween(any(), any()) } returns emptyList()

            val result =
                baselineComputer.computeHrvBaselineBetween(
                    fromMs = fromMs,
                    toMs = toMs,
                    hrvBaselineOverride = null,
                    zoneId = zone,
                )
            assertNull(result)
        }

    @Test
    fun `computeHrvBaselineBetween excludes nights failing validation`() =
        runTest {
            val customCalculator =
                mockk<ScoringCalculator>().apply {
                    io.mockk.every {
                        validateNight(any(), any(), any(), any(), any(), any())
                    } returns ScoringCalculator.NightValidationResult(
                        rmssdValid = false,
                        rhrValid = true,
                        durationValid = true,
                        stagesValid = true,
                        stagesSuspicious = false,
                        hrCoverageValid = true,
                    )
                }
            val computerWithCustomValidation = BaselineComputer(repository, customCalculator)
            val fromMs = Instant.parse("2026-06-03T00:00:00Z").toEpochMilli()
            val toMs = Instant.parse("2026-06-04T00:00:00Z").toEpochMilli()
            val session =
                createSession(
                    id = "s_invalid_hrv",
                    startIso = "2026-06-01T23:00:00Z",
                    endIso = "2026-06-02T07:00:00Z",
                )
            coEvery { repository.getDailySummaryByDate(fromMs, any()) } returns null
            coEvery { repository.getSleepSessionsBetween(any(), any()) } returns listOf(session)
            coEvery { repository.getSleepRmssdForSessionsMap(any()) } returns mapOf("s_invalid_hrv" to listOf(50f))
            coEvery { repository.getSleepHrProjectionForSessions(any()) } returns emptyList()

            val result =
                computerWithCustomValidation.computeHrvBaselineBetween(
                    fromMs = fromMs,
                    toMs = toMs,
                    hrvBaselineOverride = null,
                    zoneId = ZoneId.of("UTC"),
                )
            assertNull(result)
        }

    @Test
    fun `computeHrvBaselineBetween biphasic sleep merges core sessions into one sleep-day contribution`() =
        runTest {
            val zone = ZoneId.of("UTC")
            val policy =
                SleepDayPolicy(
                    coreMergeGapMinutes = 60,
                    supplementalCutoffMinutesOfDay = 1080,
                    minimumCountedSleepSegmentMinutes = 30,
                    supplementalArchitectureCoveragePercent = 70,
                    scoringZoneId = zone,
                )

            val s1A =
                createSession("s1A", "2026-05-31T23:00:00Z", "2026-06-01T03:00:00Z", 240, 60, 60, 120, 0.9f)
            val s1B =
                createSession("s1B", "2026-06-01T03:30:00Z", "2026-06-01T07:00:00Z", 210, 50, 50, 110, 0.9f)
            val s2 = createSession("s2", "2026-06-01T23:00:00Z", "2026-06-02T07:00:00Z")

            val fromMs = Instant.parse("2026-06-03T00:00:00Z").toEpochMilli()
            val toMs = Instant.parse("2026-06-04T00:00:00Z").toEpochMilli()

            coEvery { repository.getDailySummaryByDate(fromMs, zone) } returns null
            coEvery { repository.getSleepSessionsBetween(any(), any()) } returns listOf(s1A, s1B, s2)
            coEvery {
                repository.getSleepRmssdForSessionsMap(listOf("s1A", "s1B", "s2"))
            } returns mapOf("s1A" to listOf(40f), "s1B" to listOf(60f), "s2" to listOf(80f))
            coEvery { repository.getSleepHrProjectionForSessions(any()) } returns emptyList()

            val result =
                baselineComputer.computeHrvBaselineBetween(
                    fromMs = fromMs,
                    toMs = toMs,
                    hrvBaselineOverride = null,
                    zoneId = zone,
                    sleepDayPolicy = policy,
                )

            // Night 1 merged HRV mean = mean(40, 60) = 50, Night 2 HRV mean = 80 -> median = 65 (not 60)
            assertEquals(65, result)
            assertNotEquals(60, result)
        }

    private fun createSession(
        id: String,
        startIso: String,
        endIso: String,
        durationMinutes: Int = 480,
        deepSleepMinutes: Int = 100,
        remSleepMinutes: Int = 100,
        lightSleepMinutes: Int = 280,
        efficiency: Float = 0.95f,
    ): SleepSession =
        SleepSession(
            id = id,
            startTime = Instant.parse(startIso).toEpochMilli(),
            endTime = Instant.parse(endIso).toEpochMilli(),
            durationMinutes = durationMinutes,
            efficiency = efficiency,
            deepSleepMinutes = deepSleepMinutes,
            remSleepMinutes = remSleepMinutes,
            lightSleepMinutes = lightSleepMinutes,
            awakeMinutes = 0,
        )
}
