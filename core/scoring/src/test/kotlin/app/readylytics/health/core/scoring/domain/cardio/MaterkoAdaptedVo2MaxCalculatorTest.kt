package app.readylytics.health.core.scoring.domain.cardio

import kotlin.math.ln
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaterkoAdaptedVo2MaxCalculatorTest {
    private val calculator = MaterkoAdaptedVo2MaxCalculator()

    private fun hrvMu(rmssdMs: Float): Float = ln(rmssdMs.toDouble()).toFloat()

    @Test
    fun `returns null when not calibrated`() {
        assertNull(calculator.estimate(60f, hrvMu(50f), isCalibrated = false))
    }

    @Test
    fun `returns null for implausible rhr baseline`() {
        assertNull(calculator.estimate(20f, hrvMu(50f), true))
        assertNull(calculator.estimate(Float.NaN, hrvMu(50f), true))
    }

    @Test
    fun `returns null when hrv baseline missing`() {
        assertNull(calculator.estimate(60f, null, true))
    }

    @Test
    fun `returns null when rmssd outside health connect validation range`() {
        assertNull(calculator.estimate(60f, ln(0.5).toFloat(), true))
        assertNull(calculator.estimate(60f, ln(250.0).toFloat(), true))
    }

    @Test
    fun `returns null for out of supported domain instead of clamping`() {
        // rhr 200 -> meanRR 300 -> raw ~= -13.05 + 15 + 1.6, below MIN_SUPPORTED_VO2_MAX.
        assertNull(calculator.estimate(200f, hrvMu(50f), true))
    }

    @Test
    fun `computes expected value for representative inputs without any hrMax`() {
        // rhr 60 -> meanRR 1000; rmssd 50 -> approxPnn50 = 200*(1-Phi(1)) ~= 31.7311
        // raw = -13.05 + 50 + 1.5866 = 38.5366
        val result = calculator.estimate(60f, hrvMu(50f), true)
        assertEquals(38.54f, result!!, 0.01f)
    }

    @Test
    fun `approxPnn50 approaches zero for very low rmssd`() {
        assertTrue(calculator.approxPnn50(0.001f) < 1f)
    }

    @Test
    fun `approxPnn50 is monotonic increasing with rmssd`() {
        assertTrue(calculator.approxPnn50(20f) < calculator.approxPnn50(50f))
        assertTrue(calculator.approxPnn50(50f) < calculator.approxPnn50(100f))
    }

    @Test
    fun `approxPnn50 stays within zero and one hundred`() {
        listOf(5f, 20f, 50f, 100f, 200f).forEach { rmssd ->
            val p = calculator.approxPnn50(rmssd)
            assertTrue("pnn50=$p for rmssd=$rmssd", p in 0f..100f)
        }
    }

    @Test
    fun `standard normal cdf matches reference values`() {
        val tol = 1e-4
        assertEquals(0.5, calculator.standardNormalCdf(0.0), tol)
        assertEquals(0.84134, calculator.standardNormalCdf(1.0), tol)
        assertEquals(0.15866, calculator.standardNormalCdf(-1.0), tol)
        assertEquals(0.97500, calculator.standardNormalCdf(1.96), tol)
    }
}
