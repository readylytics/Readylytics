package app.readylytics.health.domain.scoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DailyRasIncreaseTest {
    @Test
    fun `returns null when data tenure is less than 7 days`() {
        val result = calculateDailyRasIncrease(
            dataTenureDays = 6,
            todayRas = 75f,
            yesterdayRas = 70f,
        )
        assertNull(result)
    }

    @Test
    fun `returns null when today RAS is null`() {
        val result = calculateDailyRasIncrease(
            dataTenureDays = 7,
            todayRas = null,
            yesterdayRas = 70f,
        )
        assertNull(result)
    }

    @Test
    fun `returns null when yesterday RAS is null`() {
        val result = calculateDailyRasIncrease(
            dataTenureDays = 7,
            todayRas = 75f,
            yesterdayRas = null,
        )
        assertNull(result)
    }

    @Test
    fun `returns positive delta when today RAS is higher than yesterday`() {
        val result = calculateDailyRasIncrease(
            dataTenureDays = 7,
            todayRas = 75f,
            yesterdayRas = 70f,
        )
        assertEquals(5f, result!!, 0.001f)
    }

    @Test
    fun `clamps negative delta to zero`() {
        val result = calculateDailyRasIncrease(
            dataTenureDays = 7,
            todayRas = 60f,
            yesterdayRas = 70f,
        )
        assertEquals(0f, result!!, 0.001f)
    }

    @Test
    fun `returns zero when RAS values are equal`() {
        val result = calculateDailyRasIncrease(
            dataTenureDays = 7,
            todayRas = 70f,
            yesterdayRas = 70f,
        )
        assertEquals(0f, result!!, 0.001f)
    }

    @Test
    fun `works with exactly 7 days tenure`() {
        val result = calculateDailyRasIncrease(
            dataTenureDays = 7,
            todayRas = 80f,
            yesterdayRas = 75f,
        )
        assertEquals(5f, result!!, 0.001f)
    }

    @Test
    fun `works with more than 7 days tenure`() {
        val result = calculateDailyRasIncrease(
            dataTenureDays = 30,
            todayRas = 85f,
            yesterdayRas = 80f,
        )
        assertEquals(5f, result!!, 0.001f)
    }
}
