package app.readylytics.health.data.healthconnect

import org.junit.Test
import kotlin.test.assertEquals

class WorkoutMapperIntensityTest {
    @Test
    fun computeMetrics_returns_valid_metrics() {
        val thresholds = WorkoutMapper.zoneThresholds()
        val metrics = WorkoutMapper.computeMetrics(
            startMs = 0,
            endMs = 3600000L, // 60 minutes
            hrSamples = emptyList(),
            thresholds = thresholds,
        )
        assertEquals(60, metrics.durationMinutes)
        assertEquals(0f, metrics.trimp)
    }

    @Test
    fun zoneThresholds_returns_correct_boundaries() {
        val thresholds = WorkoutMapper.zoneThresholds()
        assertEquals(5, thresholds.size)
        assertEquals(95, thresholds[0]) // z1Min
        assertEquals(114, thresholds[1]) // z1Max
    }
}
