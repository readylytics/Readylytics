package app.readylytics.health.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AdvisorDataConfidenceTest {
    @Test
    fun testConfidenceResolution() {
        assertEquals(AdvisorDataConfidence.LOW, resolveAdvisorConfidence(CalibrationPhase.CALIBRATION, false, false))
        assertEquals(AdvisorDataConfidence.LOW, resolveAdvisorConfidence(CalibrationPhase.EARLY_BASELINE, false, false))

        assertEquals(AdvisorDataConfidence.MEDIUM, resolveAdvisorConfidence(CalibrationPhase.MATURING, false, false))
        assertEquals(AdvisorDataConfidence.LOW, resolveAdvisorConfidence(CalibrationPhase.MATURING, true, false))

        assertEquals(AdvisorDataConfidence.HIGH, resolveAdvisorConfidence(CalibrationPhase.MATURE, false, false))
        assertEquals(AdvisorDataConfidence.MEDIUM, resolveAdvisorConfidence(CalibrationPhase.MATURE, true, false))

        // Everyday source degrades confidence if low
        assertEquals(AdvisorDataConfidence.MEDIUM, resolveAdvisorConfidence(CalibrationPhase.MATURE, false, true))
    }
}
