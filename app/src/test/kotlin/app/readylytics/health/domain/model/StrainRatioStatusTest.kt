package app.readylytics.health.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class StrainRatioStatusTest {
    @Test
    fun testOptimalRange() {
        assertEquals(MetricStatus.OPTIMAL, 0.8f.strainRatioStatus())
        assertEquals(MetricStatus.OPTIMAL, 1.0f.strainRatioStatus())
        assertEquals(MetricStatus.OPTIMAL, 1.3f.strainRatioStatus())
    }

    @Test
    fun testNeutralRange() {
        assertEquals(MetricStatus.NEUTRAL, 1.31f.strainRatioStatus())
        assertEquals(MetricStatus.NEUTRAL, 1.5f.strainRatioStatus())
    }

    @Test
    fun testOvertainingWarning() {
        assertEquals(MetricStatus.WARNING, 1.51f.strainRatioStatus())
        assertEquals(MetricStatus.WARNING, 2.0f.strainRatioStatus())
    }

    @Test
    fun testSevereOvertraining() {
        assertEquals(MetricStatus.POOR, 2.01f.strainRatioStatus())
        assertEquals(MetricStatus.POOR, 5.0f.strainRatioStatus())
    }

    @Test
    fun testUndertrainingWarning() {
        assertEquals(MetricStatus.WARNING, 0.5f.strainRatioStatus())
        assertEquals(MetricStatus.WARNING, 0.79f.strainRatioStatus())
    }

    @Test
    fun testSevereUndertraining() {
        assertEquals(MetricStatus.POOR, 0.49f.strainRatioStatus())
        assertEquals(MetricStatus.POOR, 0.0f.strainRatioStatus())
    }

    @Test
    fun testNegativeValues() {
        assertEquals(MetricStatus.CALIBRATING, (-0.5f).strainRatioStatus())
        assertEquals(MetricStatus.CALIBRATING, (-1.0f).strainRatioStatus())
    }
}
