package app.readylytics.health.core.model.domain.service

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
