package app.readylytics.health.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class StatusToMetricStatusTest {
    @Test
    fun `BloodPressureStatus maps to expected MetricStatus`() {
        assertEquals(MetricStatus.OPTIMAL, BloodPressureStatus.Optimal.toMetricStatus())
        assertEquals(MetricStatus.NEUTRAL, BloodPressureStatus.Neutral.toMetricStatus())
        assertEquals(MetricStatus.WARNING, BloodPressureStatus.HypertensionStage1.toMetricStatus())
        assertEquals(MetricStatus.POOR, BloodPressureStatus.HypertensionStage2.toMetricStatus())
    }

    @Test
    fun `BmiStatus maps to expected MetricStatus`() {
        assertEquals(MetricStatus.OPTIMAL, BmiStatus.Optimal.toMetricStatus())
        assertEquals(MetricStatus.NEUTRAL, BmiStatus.Neutral.toMetricStatus())
        assertEquals(MetricStatus.WARNING, BmiStatus.Warning.toMetricStatus())
        assertEquals(MetricStatus.POOR, BmiStatus.Poor.toMetricStatus())
    }
}
