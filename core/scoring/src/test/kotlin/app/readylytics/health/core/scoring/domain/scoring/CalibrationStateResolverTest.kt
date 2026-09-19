package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.scoring.domain.scoring.components.Phase
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalibrationStateResolverTest {
    @Test
    fun `phase boundaries preserve current constants`() {
        val expected =
            mapOf(
                6 to Phase.CALIBRATION,
                7 to Phase.EARLY_BASELINE,
                20 to Phase.EARLY_BASELINE,
                21 to Phase.MATURING,
                29 to Phase.MATURING,
                30 to Phase.MATURING,
                59 to Phase.MATURING,
                60 to Phase.MATURE,
            )
        expected.forEach { (count, phase) ->
            assertEquals(phase, resolveCalibrationState(count, null, null).phase)
            assertEquals(count < 7, resolveCalibrationState(count, null, null).isCalibrating)
        }
        assertEquals(Phase.MATURE, resolveCalibrationState(0, 60, Phase.MATURE).phase)
    }

    @Test
    fun `zero live count is a known calibrating state, not unknown`() {
        val state = resolveCalibrationState(liveCount = 0, frozenCount = null, frozenPhase = null)
        assertEquals(0, state.observationCount)
        assertEquals(Phase.CALIBRATION, state.phase)
        assertTrue(state.isCalibrating)
    }

    @Test
    fun `null live count with no frozen data resolves to unknown and still calibrating`() {
        val state = resolveCalibrationState(liveCount = null, frozenCount = null, frozenPhase = null)
        assertNull(state.observationCount)
        assertNull(state.phase)
        assertTrue(state.isCalibrating)
    }

    @Test
    fun `frozen phase wins over live count even when live count disagrees`() {
        // A replay with no live history loaded (liveCount reflects an incomplete recompute) must
        // never override a validated frozen snapshot.
        val state = resolveCalibrationState(liveCount = 0, frozenCount = 65, frozenPhase = Phase.MATURE)
        assertEquals(65, state.observationCount)
        assertEquals(Phase.MATURE, state.phase)
        assertFalse(state.isCalibrating)
    }

    @Test
    fun `frozen phase without a frozen count is preserved as unknown-count evidence`() {
        // Legacy metadata: the phase was validated and persisted, but the count itself is unknown
        // (e.g. pre-C2 data). Must not fabricate a count, and must not fall back to live scanning.
        val state = resolveCalibrationState(liveCount = 42, frozenCount = null, frozenPhase = Phase.MATURE)
        assertNull(state.observationCount)
        assertEquals(Phase.MATURE, state.phase)
        assertFalse(state.isCalibrating)
    }

    @Test
    fun `mismatched frozen count and phase is treated as needing repair, never as mature`() {
        val state = resolveCalibrationState(liveCount = null, frozenCount = 5, frozenPhase = Phase.MATURE)
        assertNull(state.observationCount)
        assertNull(state.phase)
        assertTrue(state.isCalibrating)
    }

    @Test
    fun `consistent frozen count and phase resolve deterministically`() {
        val first = resolveCalibrationState(liveCount = null, frozenCount = 60, frozenPhase = Phase.MATURE)
        val second = resolveCalibrationState(liveCount = null, frozenCount = 60, frozenPhase = Phase.MATURE)
        assertEquals(first, second)
        assertEquals(60, first.observationCount)
        assertEquals(Phase.MATURE, first.phase)
        assertFalse(first.isCalibrating)
    }

    @Test
    fun `negative observation count is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            resolveCalibrationState(liveCount = -1, frozenCount = null, frozenPhase = null)
        }
        assertFailsWith<IllegalArgumentException> {
            resolveCalibrationState(liveCount = null, frozenCount = -3, frozenPhase = null)
        }
    }
}
