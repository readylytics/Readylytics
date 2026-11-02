package app.readylytics.health.core.scoring.domain.scoring.sleep

import app.readylytics.health.core.model.domain.model.HeartRateRecord
import org.junit.Test
import kotlin.test.assertEquals

class IntervalCoverageAccumulatorTest {

    private fun hr(timestampMs: Long) = HeartRateRecord(
        id = "hr_$timestampMs",
        timestampMs = timestampMs,
        beatsPerMinute = 60,
        recordType = "SLEEP",
    )

    @Test
    fun `returns 0 ms for empty records`() {
        val coverage = IntervalCoverageAccumulator.calculateCoverageMs(
            sessionStartMs = 1000L,
            sessionEndMs = 5000L,
            hrRecords = emptyList<HeartRateRecord>(),
        )
        assertEquals(0L, coverage)
    }

    @Test
    fun `returns single minute capped at duration for single record`() {
        val coverage = IntervalCoverageAccumulator.calculateCoverageMs(
            sessionStartMs = 1000L,
            sessionEndMs = 2000L, // 1 second span
            hrRecords = listOf(hr(1500L)),
        )
        assertEquals(1000L, coverage) // Capped at session duration
    }

    @Test
    fun `caps inter-sample intervals exceeding maxGapMs`() {
        val start = 0L
        val end = 8 * 3600 * 1000L // 8 hours = 28,800,000 ms
        // Two samples separated by 7 hours (25,200,000 ms)
        val records = listOf(
            hr(1000L),
            hr(7 * 3600 * 1000L),
        )

        val coverage = IntervalCoverageAccumulator.calculateCoverageMs(
            sessionStartMs = start,
            sessionEndMs = end,
            hrRecords = records,
            maxGapMs = 5 * 60 * 1000L, // 300,000 ms
        )

        // Bounded to 300,000 ms + 60,000 ms = 360,000 ms (6 minutes), NOT 7 hours
        assertEquals(360_000L, coverage)
    }

    @Test
    fun `accumulates full time for dense sampling under maxGapMs`() {
        val start = 0L
        val end = 10 * 60 * 1000L // 10 minutes
        // Samples every 1 minute
        val records = (0..10).map { hr(it * 60 * 1000L) }

        val coverage = IntervalCoverageAccumulator.calculateCoverageMs(
            sessionStartMs = start,
            sessionEndMs = end,
            hrRecords = records,
        )

        // 10 intervals of 60s + 60s base = 660s, capped at total duration (600,000 ms)
        assertEquals(end, coverage)
    }
}
