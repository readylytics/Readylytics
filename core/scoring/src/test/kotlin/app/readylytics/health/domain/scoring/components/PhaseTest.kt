package app.readylytics.health.domain.scoring.components

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhaseTest {
    @Test
    fun `CALIBRATION phase has correct display name`() {
        assertEquals("Calibrating", Phase.CALIBRATION.displayName)
    }

    @Test
    fun `EARLY_BASELINE phase has correct display name`() {
        assertEquals("Early Baseline", Phase.EARLY_BASELINE.displayName)
    }

    @Test
    fun `MATURING phase has correct display name`() {
        assertEquals("Maturing", Phase.MATURING.displayName)
    }

    @Test
    fun `MATURE phase has correct display name`() {
        assertEquals("Mature", Phase.MATURE.displayName)
    }

    @Test
    fun `CALIBRATION has NOT_READY confidence`() {
        assertEquals(ConfidenceLevel.NOT_READY, Phase.CALIBRATION.confidence)
    }

    @Test
    fun `EARLY_BASELINE has LOW confidence`() {
        assertEquals(ConfidenceLevel.LOW, Phase.EARLY_BASELINE.confidence)
    }

    @Test
    fun `MATURING has MEDIUM confidence`() {
        assertEquals(ConfidenceLevel.MEDIUM, Phase.MATURING.confidence)
    }

    @Test
    fun `MATURE has HIGH confidence`() {
        assertEquals(ConfidenceLevel.HIGH, Phase.MATURE.confidence)
    }

    @Test
    fun `all phase values are defined`() {
        val values = Phase.values()
        assertEquals(4, values.size)
    }

    @Test
    fun `phase values in correct order`() {
        val values = Phase.values()
        assertEquals(Phase.CALIBRATION, values[0])
        assertEquals(Phase.EARLY_BASELINE, values[1])
        assertEquals(Phase.MATURING, values[2])
        assertEquals(Phase.MATURE, values[3])
    }

    @Test
    fun `CALIBRATION max sessions constant is correct`() {
        assertEquals(6, Phase.CALIBRATION_MAX_SESSIONS)
    }

    @Test
    fun `EARLY_BASELINE max sessions constant is correct`() {
        assertEquals(20, Phase.EARLY_BASELINE_MAX_SESSIONS)
    }

    @Test
    fun `MATURING max sessions constant is correct`() {
        assertEquals(59, Phase.MATURING_MAX_SESSIONS)
    }

    @Test
    fun `phase progression goes from low to high confidence`() {
        val phases = Phase.values()
        for (i in 0 until phases.size - 1) {
            val current = phases[i].confidence
            val next = phases[i + 1].confidence
            assertTrue(current.ordinal <= next.ordinal, "Confidence should increase or stay same")
        }
    }

    @Test
    fun `phase enum valueOf works`() {
        assertEquals(Phase.CALIBRATION, Phase.valueOf("CALIBRATION"))
        assertEquals(Phase.EARLY_BASELINE, Phase.valueOf("EARLY_BASELINE"))
        assertEquals(Phase.MATURING, Phase.valueOf("MATURING"))
        assertEquals(Phase.MATURE, Phase.valueOf("MATURE"))
    }

    @Test
    fun `display names are unique`() {
        val names = Phase.values().map { it.displayName }
        assertEquals(4, names.distinct().size)
    }
}
