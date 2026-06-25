package app.readylytics.health.domain.scoring

import app.readylytics.health.domain.scoring.strategies.LoadScoringStrategy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// isLateNadir: nadir (HR minimum) occurring after the last 33% of the session is a delayed nadir.
// Threshold = 0.67 * sessionDuration. durationMinutes=0 is always false (guard).
// REF: Trinder 2001 J Sleep Res 10:253
class IsLateNadirTest {
    private val calculator = LoadScoringStrategy()
    private val startMs = 0L
    private val durationMinutes = 480 // 8 hours

    private fun sessionDurationMs() = durationMinutes * 60 * 1000L

    @Test
    fun `duration zero returns false (guard)`() {
        assertFalse(calculator.isLateNadir(minHrTimestampMs = 1000L, sessionStartMs = 0L, durationMinutes = 0))
    }

    @Test
    fun `nadir at exactly 67 percent is not late (boundary)`() {
        // 0.67 * 8h = 5h 21m 36s = 19296000ms; offset == threshold → NOT strictly greater
        val offsetMs = (sessionDurationMs() * 0.67f).toLong()
        assertFalse(
            calculator.isLateNadir(
                minHrTimestampMs = startMs + offsetMs,
                sessionStartMs = startMs,
                durationMinutes = durationMinutes,
            ),
        )
    }

    @Test
    fun `nadir at 68 percent is late`() {
        val offsetMs = (sessionDurationMs() * 0.68f).toLong()
        assertTrue(
            calculator.isLateNadir(
                minHrTimestampMs = startMs + offsetMs,
                sessionStartMs = startMs,
                durationMinutes = durationMinutes,
            ),
        )
    }

    @Test
    fun `nadir at 50 percent is not late`() {
        val offsetMs = (sessionDurationMs() * 0.50f).toLong()
        assertFalse(
            calculator.isLateNadir(
                minHrTimestampMs = startMs + offsetMs,
                sessionStartMs = startMs,
                durationMinutes = durationMinutes,
            ),
        )
    }

    @Test
    fun `nadir at end of session is late`() {
        assertTrue(
            calculator.isLateNadir(
                minHrTimestampMs = startMs + sessionDurationMs() - 1L,
                sessionStartMs = startMs,
                durationMinutes = durationMinutes,
            ),
        )
    }

    @Test
    fun `nadir before session start returns false`() {
        // Negative offset → (negative) NOT > threshold
        assertFalse(
            calculator.isLateNadir(
                minHrTimestampMs = startMs - 1000L,
                sessionStartMs = startMs,
                durationMinutes = durationMinutes,
            ),
        )
    }
}
