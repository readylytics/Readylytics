package app.readylytics.health.domain.service

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class BodyTemperatureBaselineCalculatorTest {
    private val calculator = BodyTemperatureBaselineCalculator()

    @Test
    fun `fewer than 14 non-null days returns null (Calibrating)`() {
        val thirteenDays = List(13) { 36.5f }
        assertNull(calculator.calculateBaseline(thirteenDays))
    }

    @Test
    fun `exactly 14 non-null days returns their average`() {
        val fourteenDays = List(14) { 36.5f } + List(0) { }
        assertEquals(36.5f, calculator.calculateBaseline(List(14) { 36.5f })!!, 0.001f)
    }

    @Test
    fun `more than 14 non-null days still averages all provided values`() {
        val values = List(20) { index -> 36f + index * 0.1f }
        val expected = values.average().toFloat()
        assertEquals(expected, calculator.calculateBaseline(values)!!, 0.001f)
    }

    @Test
    fun `empty list returns null`() {
        assertNull(calculator.calculateBaseline(emptyList()))
    }

    @Test
    fun `isElevated is true only when the absolute deviation meets or exceeds the threshold`() {
        assertFalse(calculator.isElevated(todayCelsius = 36.9f, baselineCelsius = 36.5f, thresholdCelsius = 1.0f))
        assertTrue(calculator.isElevated(todayCelsius = 37.5f, baselineCelsius = 36.5f, thresholdCelsius = 1.0f))
        assertTrue(calculator.isElevated(todayCelsius = 37.5f, baselineCelsius = 36.5f, thresholdCelsius = 0.25f))
        // Symmetric: an unusually LOW deviation of the same magnitude also counts.
        assertTrue(calculator.isElevated(todayCelsius = 35.4f, baselineCelsius = 36.5f, thresholdCelsius = 1.0f))
        // Boundary: exactly at threshold counts as elevated ("meets or exceeds").
        assertTrue(calculator.isElevated(todayCelsius = 37.5f, baselineCelsius = 36.5f, thresholdCelsius = 1.0f))
    }
}
