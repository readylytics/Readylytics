package app.readylytics.health.feature.sleep

import app.readylytics.health.core.model.domain.repository.HeartRateRecordData
import org.junit.Assert.assertEquals
import org.junit.Test

class SleepHrChartHelperTest {
    private fun sample(
        timestampMs: Long,
        bpm: Int,
    ) = HeartRateRecordData(
        id = "id_$timestampMs",
        timestampMs = timestampMs,
        beatsPerMinute = bpm,
        recordType = "SLEEP",
    )

    @Test
    fun `splits into separate segments at gaps larger than the threshold`() {
        val samples =
            listOf(
                sample(1000L, 55),
                sample(2000L, 56),
                // gap > 5000ms threshold
                sample(8000L, 58),
                sample(9000L, 57),
            )

        val segments = SleepHrChartHelper.splitIntoSegments(samples, gapThresholdMs = 5000L)

        assertEquals(2, segments.size)
        assertEquals(2, segments[0].size)
        assertEquals(2, segments[1].size)
        assertEquals(1000L, segments[0][0].timestampMs)
        assertEquals(8000L, segments[1][0].timestampMs)
    }

    @Test
    fun `single continuous run of samples stays one segment`() {
        val samples = listOf(sample(0L, 55), sample(60_000L, 56), sample(120_000L, 57))

        val segments = SleepHrChartHelper.splitIntoSegments(samples, gapThresholdMs = 600_000L)

        assertEquals(1, segments.size)
        assertEquals(3, segments[0].size)
    }

    @Test
    fun `empty input returns no segments`() {
        val segments = SleepHrChartHelper.splitIntoSegments(emptyList(), gapThresholdMs = 600_000L)

        assertEquals(0, segments.size)
    }
}
