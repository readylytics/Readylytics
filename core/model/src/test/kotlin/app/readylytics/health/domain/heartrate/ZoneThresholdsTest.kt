package app.readylytics.health.domain.heartrate

import app.readylytics.health.domain.model.DomainHeartRateSample
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class ZoneThresholdsTest {
    @Test
    fun testComputeMetrics() {
        val startMs = 1000L
        val endMs = 61000L
        val samples = listOf(
            DomainHeartRateSample(Instant.ofEpochMilli(1000L), 100), // Zone 1 Min is 95
            DomainHeartRateSample(Instant.ofEpochMilli(31000L), 120), // Zone 2 Max is 133
        )
        val thresholds = ZoneThresholds.zoneThresholds()
        val metrics = ZoneThresholds.computeMetrics(startMs, endMs, samples, thresholds)
        assertEquals(1, metrics.durationMinutes)
        assertEquals(110.0f, metrics.avgHr)
    }
}
