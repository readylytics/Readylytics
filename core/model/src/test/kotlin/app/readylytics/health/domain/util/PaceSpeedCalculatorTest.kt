package app.readylytics.health.domain.util

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaceSpeedCalculatorTest {

    @Test
    fun `numeric exercise type IDs for pace activities return true`() {
        assertTrue(PaceSpeedCalculator.isPaceActivity("56")) // Running
        assertTrue(PaceSpeedCalculator.isPaceActivity("57")) // Running - Treadmill
        assertTrue(PaceSpeedCalculator.isPaceActivity("79")) // Walking
        assertTrue(PaceSpeedCalculator.isPaceActivity("78")) // Hiking
        assertTrue(PaceSpeedCalculator.isPaceActivity("34")) // Hiking
    }

    @Test
    fun `string exercise names for pace activities return true case-insensitively`() {
        assertTrue(PaceSpeedCalculator.isPaceActivity("Running"))
        assertTrue(PaceSpeedCalculator.isPaceActivity("running"))
        assertTrue(PaceSpeedCalculator.isPaceActivity("RUNNING"))
        assertTrue(PaceSpeedCalculator.isPaceActivity("Walking"))
        assertTrue(PaceSpeedCalculator.isPaceActivity("walking"))
        assertTrue(PaceSpeedCalculator.isPaceActivity("Hiking"))
        assertTrue(PaceSpeedCalculator.isPaceActivity("hiking"))
        assertTrue(PaceSpeedCalculator.isPaceActivity("Treadmill"))
        assertTrue(PaceSpeedCalculator.isPaceActivity("treadmill"))
        assertTrue(PaceSpeedCalculator.isPaceActivity("run"))
        assertTrue(PaceSpeedCalculator.isPaceActivity("walk"))
        assertTrue(PaceSpeedCalculator.isPaceActivity("hike"))
        assertTrue(PaceSpeedCalculator.isPaceActivity("  Running  "))
        assertTrue(PaceSpeedCalculator.isPaceActivity("  56  "))
    }

    @Test
    fun `cycling and skiing are speed activities`() {
        assertFalse(PaceSpeedCalculator.isPaceActivity("8"))
        assertFalse(PaceSpeedCalculator.isPaceActivity("9"))
        assertFalse(PaceSpeedCalculator.isPaceActivity("61"))
        assertFalse(PaceSpeedCalculator.isPaceActivity("Cycling"))
        assertFalse(PaceSpeedCalculator.isPaceActivity("cycling"))
        assertFalse(PaceSpeedCalculator.isPaceActivity("Skiing"))
    }

    @Test
    fun `other activities are treated as speed activities`() {
        assertFalse(PaceSpeedCalculator.isPaceActivity("70"))
        assertFalse(PaceSpeedCalculator.isPaceActivity("74"))
        assertFalse(PaceSpeedCalculator.isPaceActivity("Swimming"))
        assertFalse(PaceSpeedCalculator.isPaceActivity("Rowing"))
        assertFalse(PaceSpeedCalculator.isPaceActivity("Yoga"))
        assertFalse(PaceSpeedCalculator.isPaceActivity(""))
        assertFalse(PaceSpeedCalculator.isPaceActivity("   "))
    }

    @Test
    fun `speed mps converts to kmh`() {
        assertEquals(9.0, PaceSpeedCalculator.speedMpsToSpeedKmh(2.5), 0.01)
        assertEquals(0.0, PaceSpeedCalculator.speedMpsToSpeedKmh(0.0), 0.01)
    }

    @Test
    fun `speed mps converts to pace minutes per km`() {
        // 3 m/s = 10.8 km/h -> (1000 / 3) / 60 = 5.5555... min/km
        assertEquals(5.56, PaceSpeedCalculator.speedMpsToPaceMinKm(3.0), 0.01)
    }

    @Test
    fun `pace is capped at max pace minutes per km`() {
        assertEquals(20.0, PaceSpeedCalculator.speedMpsToPaceMinKm(0.05), 0.01)
        assertEquals(20.0, PaceSpeedCalculator.speedMpsToPaceMinKm(0.01), 0.01)
        assertEquals(20.0, PaceSpeedCalculator.speedMpsToPaceMinKm(0.0), 0.01)
    }

    @Test
    fun `negative velocity is treated as standstill`() {
        assertEquals(0.0, PaceSpeedCalculator.speedMpsToSpeedKmh(-1.0), 0.01)
        assertEquals(20.0, PaceSpeedCalculator.speedMpsToPaceMinKm(-1.0), 0.01)
    }
}

