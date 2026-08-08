package app.readylytics.health.domain.model

import app.readylytics.health.domain.repository.SleepSessionData
import org.junit.Assert.assertEquals
import org.junit.Test

class MetricStatusExtensionsTest {
    @Test
    fun scoreStatus_classifiesNullAndDashboardBoundaries() {
        assertEquals(MetricStatus.CALIBRATING, (null as Float?).scoreStatus())
        assertEquals(MetricStatus.CALIBRATING, Float.NaN.scoreStatus())
        assertEquals(MetricStatus.POOR, 39.99f.scoreStatus())
        assertEquals(MetricStatus.WARNING, 40f.scoreStatus())
        assertEquals(MetricStatus.WARNING, 59.99f.scoreStatus())
        assertEquals(MetricStatus.NEUTRAL, 60f.scoreStatus())
        assertEquals(MetricStatus.NEUTRAL, 84.99f.scoreStatus())
        assertEquals(MetricStatus.OPTIMAL, 85f.scoreStatus())
    }

    @Test
    fun sleepEfficiencyStatus_classifiesNullAndDashboardBoundaries() {
        assertEquals(MetricStatus.CALIBRATING, (null as Float?).sleepEfficiencyStatus())
        assertEquals(MetricStatus.CALIBRATING, Float.NaN.sleepEfficiencyStatus())
        assertEquals(MetricStatus.POOR, 69.99f.sleepEfficiencyStatus())
        assertEquals(MetricStatus.WARNING, 70f.sleepEfficiencyStatus())
        assertEquals(MetricStatus.WARNING, 79.99f.sleepEfficiencyStatus())
        assertEquals(MetricStatus.NEUTRAL, 80f.sleepEfficiencyStatus())
        assertEquals(MetricStatus.NEUTRAL, 84.99f.sleepEfficiencyStatus())
        assertEquals(MetricStatus.OPTIMAL, 85f.sleepEfficiencyStatus())
    }

    @Test
    fun circadianConsistencyStatus_classifiesNullAndDashboardBoundaries() {
        assertEquals(MetricStatus.CALIBRATING, (null as Float?).circadianConsistencyStatus())
        assertEquals(MetricStatus.CALIBRATING, Float.NaN.circadianConsistencyStatus())
        assertEquals(MetricStatus.POOR, 39.99f.circadianConsistencyStatus())
        assertEquals(MetricStatus.WARNING, 40f.circadianConsistencyStatus())
        assertEquals(MetricStatus.WARNING, 59.99f.circadianConsistencyStatus())
        assertEquals(MetricStatus.NEUTRAL, 60f.circadianConsistencyStatus())
        assertEquals(MetricStatus.NEUTRAL, 79.99f.circadianConsistencyStatus())
        assertEquals(MetricStatus.OPTIMAL, 80f.circadianConsistencyStatus())
    }

    @Test
    fun strainRatioStatus_classifiesDashboardBoundaries() {
        assertEquals(MetricStatus.POOR, 0.49f.strainRatioStatus())
        assertEquals(MetricStatus.WARNING, 0.5f.strainRatioStatus())
        assertEquals(MetricStatus.WARNING, 0.79f.strainRatioStatus())
        assertEquals(MetricStatus.OPTIMAL, 0.8f.strainRatioStatus())
        assertEquals(MetricStatus.OPTIMAL, 1.3f.strainRatioStatus())
        assertEquals(MetricStatus.NEUTRAL, Math.nextUp(1.3f).strainRatioStatus())
        assertEquals(MetricStatus.NEUTRAL, 1.5f.strainRatioStatus())
        assertEquals(MetricStatus.WARNING, Math.nextUp(1.5f).strainRatioStatus())
        assertEquals(MetricStatus.WARNING, 2.0f.strainRatioStatus())
        assertEquals(MetricStatus.POOR, Math.nextUp(2.0f).strainRatioStatus())
        assertEquals(MetricStatus.CALIBRATING, (-0.01f).strainRatioStatus())
        assertEquals(MetricStatus.CALIBRATING, Float.NaN.strainRatioStatus())
    }

    @Test
    fun sleepEfficiencyStatus_normalizesLegacyFractionsBeforeClassification() {
        assertEquals(MetricStatus.WARNING, 0.70f.sleepEfficiencyStatus())
        assertEquals(MetricStatus.WARNING, 70f.sleepEfficiencyStatus())
        assertEquals(MetricStatus.NEUTRAL, 0.80f.sleepEfficiencyStatus())
        assertEquals(MetricStatus.NEUTRAL, 80f.sleepEfficiencyStatus())
        assertEquals(MetricStatus.OPTIMAL, 0.85f.sleepEfficiencyStatus())
        assertEquals(MetricStatus.OPTIMAL, 85f.sleepEfficiencyStatus())
    }

    @Test
    fun efficiencyStatus_wrappers_preserveLegacyNaNAsPoor() {
        val session = sleepSession(efficiency = Float.NaN)
        val summary = SleepSessionSummary(efficiency = Float.NaN, startTime = 0L, endTime = 0L)

        assertEquals(MetricStatus.CALIBRATING, Float.NaN.sleepEfficiencyStatus())
        assertEquals(MetricStatus.POOR, session.efficiencyStatus())
        assertEquals(MetricStatus.POOR, summary.efficiencyStatus())
    }

    private fun sleepSession(efficiency: Float) =
        SleepSessionData(
            id = "session-id",
            deviceName = null,
            startTime = 0L,
            endTime = 0L,
            durationMinutes = 0,
            efficiency = efficiency,
            deepSleepMinutes = 0,
            lightSleepMinutes = 0,
            remSleepMinutes = 0,
            awakeMinutes = 0,
        )
}
