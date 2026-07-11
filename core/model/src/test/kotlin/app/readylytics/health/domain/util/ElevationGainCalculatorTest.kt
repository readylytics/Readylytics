package app.readylytics.health.domain.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ElevationGainCalculatorTest {
    @Test
    fun testAscentCalculationIgnoresNoise() {
        val alt = listOf(100.0, 101.0, 100.5, 102.0, 100.0, 105.0) // Jitter should be ignored/smoothed
        val ascent = ElevationGainCalculator.calculateAscent(alt, 3.0)
        assertEquals(0.0, ascent, 0.1)
    }

    @Test
    fun testAscentCalculationCountsTrueGain() {
        val alt = listOf(100.0, 100.0, 100.0, 100.0, 100.0, 115.0, 115.0, 115.0, 115.0, 115.0)
        val ascent = ElevationGainCalculator.calculateAscent(alt, 3.0)
        assertEquals(15.0, ascent, 0.1)
    }
}
