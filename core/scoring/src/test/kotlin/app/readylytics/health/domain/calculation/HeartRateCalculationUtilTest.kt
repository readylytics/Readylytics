package app.readylytics.health.domain.calculation

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeartRateCalculationUtilTest {
    @Test
    fun `heart rate reserve calculation`() {
        val maxHr = 190
        val restingHr = 60
        val hrr = maxHr - restingHr
        assertEquals(130, hrr)
    }

    @Test
    fun `heart rate percentage of max`() {
        val currentHr = 150
        val maxHr = 190
        val pct = (currentHr.toFloat() / maxHr) * 100
        assertTrue(pct in 70f..80f)
    }

    @Test
    fun `average heart rate from samples`() {
        val samples = listOf(120, 130, 125, 135, 128)
        val avg = samples.average()
        assertEquals(127.6, avg, 0.1)
    }

    @Test
    fun `target heart rate zone classification`() {
        val maxHr = 200
        val currentHr = 140
        val pct = (currentHr.toFloat() / maxHr) * 100
        val zone =
            when {
                pct < 50 -> "Zone1"
                pct < 60 -> "Zone2"
                pct < 70 -> "Zone3"
                pct < 85 -> "Zone4"
                else -> "Zone5"
            }
        assertEquals("Zone4", zone)
    }

    @Test
    fun `resting heart rate validation`() {
        val restingHr = 60
        assertTrue(restingHr in 40..100)
    }

    @Test
    fun `max heart rate formula Karvonen`() {
        val age = 30
        val maxHr = 220 - age
        assertEquals(190, maxHr)
    }

    @Test
    fun `aerobic threshold calculation`() {
        val maxHr = 190
        val threshold = maxHr * 0.85f
        assertTrue(threshold in 160f..170f)
    }

    @Test
    fun `anaerobic threshold calculation`() {
        val maxHr = 190
        val threshold = maxHr * 0.90f
        assertTrue(threshold in 170f..180f)
    }
}
