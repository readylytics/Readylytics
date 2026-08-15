package app.readylytics.health.domain.util

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaceSpeedCalculatorTest {

    @Test
    fun `running and walking are pace activities`() {
        assertTrue(PaceSpeedCalculator.isPaceActivity("56"))
        assertTrue(PaceSpeedCalculator.isPaceActivity("57"))
        assertTrue(PaceSpeedCalculator.isPaceActivity("79"))
    }

    @Test
    fun `cycling and skiing are speed activities`() {
        assertFalse(PaceSpeedCalculator.isPaceActivity("8"))
        assertFalse(PaceSpeedCalculator.isPaceActivity("9"))
        assertFalse(PaceSpeedCalculator.isPaceActivity("61"))
    }

    @Test
    fun `other activities are treated as speed activities`() {
        assertFalse(PaceSpeedCalculator.isPaceActivity("70"))
        assertFalse(PaceSpeedCalculator.isPaceActivity("74"))
    }

    @Test
    fun `speed mps converts to kmh`() {
        assertEquals(9.0, PaceSpeedCalculator.speedMpsToSpeedKmh(2.5), 0.01)
        assertEquals(0.0, PaceSpeedCalculator.speedMpsToSpeedKmh(0.0), 0.01)
    }

    @Test
    fun `speed mps converts to pace minutes per km`() {
        // 3 m/s = 10.8 km/h -> 60 / 10.8 = 5.56 min/km
        assertEquals(5.56, PaceSpeedCalculator.speedMpsToPaceMinKm(3.0), 0.01)
    }

    @Test
    fun `pace is capped at 20 minutes per km`() {
        assertEquals(20.0, PaceSpeedCalculator.speedMpsToPaceMinKm(0.1), 0.01)
        assertEquals(20.0, PaceSpeedCalculator.speedMpsToPaceMinKm(0.0), 0.01)
    }

    @Test
    fun `negative velocity is treated as standstill`() {
        assertEquals(0.0, PaceSpeedCalculator.speedMpsToSpeedKmh(-1.0), 0.01)
        assertEquals(20.0, PaceSpeedCalculator.speedMpsToPaceMinKm(-1.0), 0.01)
    }
}
