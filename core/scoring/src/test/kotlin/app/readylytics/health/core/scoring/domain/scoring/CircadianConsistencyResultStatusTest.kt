package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.model.domain.model.MetricStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class CircadianConsistencyResultStatusTest {
    @Test
    fun readyStatus_usesSharedCircadianBoundaries() {
        listOf(
            39.99f to MetricStatus.POOR,
            40f to MetricStatus.WARNING,
            59.99f to MetricStatus.WARNING,
            60f to MetricStatus.NEUTRAL,
            79.99f to MetricStatus.NEUTRAL,
            80f to MetricStatus.OPTIMAL,
        ).forEach { (score, expectedStatus) ->
            assertEquals(expectedStatus, ready(score).toStatus())
        }
    }

    @Test
    fun readyStatus_preservesSharedNonFiniteBehavior() {
        assertEquals(MetricStatus.CALIBRATING, ready(Float.NaN).toStatus())
    }

    @Test
    fun unavailableResults_preserveTheirDedicatedStatuses() {
        assertEquals(MetricStatus.CALIBRATING, CircadianConsistencyResult.Calibrating.toStatus())
        assertEquals(MetricStatus.NO_DATA, CircadianConsistencyResult.MissingData.toStatus())
    }

    private fun ready(score: Float) =
        CircadianConsistencyResult.Ready(
            score = score,
            medianBedtimeMinutes = 0,
            medianWakeMinutes = 0,
            thresholdMinutes = 30,
            latestBedtimeOffsetMinutes = 0,
        )
}
