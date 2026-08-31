package app.readylytics.health.core.scoring.domain.scoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DailyRasIncreaseTest {
    @Test
    fun `returns null when data tenure is less than 7 days`() {
        val result = calculateDailyRasIncrease(
            dataTenureDays = 6,
            todayRas = 5f,
        )
        assertNull(result)
    }

    @Test
    fun `returns null when today RAS is null`() {
        val result = calculateDailyRasIncrease(
            dataTenureDays = 7,
            todayRas = null,
        )
        assertNull(result)
    }

    @Test
    fun `returns today RAS when data tenure is at least 7 days`() {
        val result = calculateDailyRasIncrease(
            dataTenureDays = 7,
            todayRas = 5f,
        )
        assertEquals(5f, result!!, 0.001f)
    }

    @Test
    fun `clamps negative RAS to zero`() {
        val result = calculateDailyRasIncrease(
            dataTenureDays = 7,
            todayRas = -3f,
        )
        assertEquals(0f, result!!, 0.001f)
    }

    @Test
    fun `returns zero when today RAS is zero`() {
        val result = calculateDailyRasIncrease(
            dataTenureDays = 7,
            todayRas = 0f,
        )
        assertEquals(0f, result!!, 0.001f)
    }

    @Test
    fun `works with more than 7 days tenure`() {
        val result = calculateDailyRasIncrease(
            dataTenureDays = 30,
            todayRas = 5f,
        )
        assertEquals(5f, result!!, 0.001f)
    }
}
