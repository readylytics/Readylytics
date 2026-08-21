package app.readylytics.health.core.model.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class VitalStatusClassifiersTest {
    @Test
    fun stepsStatusClassifier_classifiesAvailabilityAndGoalBoundaries() {
        assertEquals(MetricStatus.CALIBRATING, StepsStatusClassifier.classify(null, 10_000))
        assertEquals(MetricStatus.POOR, StepsStatusClassifier.classify(4_999, 10_000))
        assertEquals(MetricStatus.WARNING, StepsStatusClassifier.classify(5_000, 10_000))
        assertEquals(MetricStatus.NEUTRAL, StepsStatusClassifier.classify(7_500, 10_000))
        assertEquals(MetricStatus.OPTIMAL, StepsStatusClassifier.classify(10_000, 10_000))
    }

    @Test
    fun stepsStatusClassifier_calibratesForInvalidGoal() {
        assertEquals(MetricStatus.CALIBRATING, StepsStatusClassifier.classify(5_000, 0))
    }

    @Test
    fun heartRateStatusClassifier_classifiesAvailability() {
        assertEquals(MetricStatus.CALIBRATING, HeartRateStatusClassifier.classify(null))
        assertEquals(MetricStatus.NEUTRAL, HeartRateStatusClassifier.classify(72))
    }
}
