package app.readylytics.health.domain.util

import org.junit.Test
import kotlin.test.assertEquals

class ElevationGainCalculatorTest {

    @Test
    fun `filters out sub-threshold noise jitter`() {
        val jittery = listOf(100.0, 100.8, 99.5, 101.2, 100.1, 102.5, 99.8)
        assertEquals(0.0, ElevationGainCalculator.calculateAscent(jittery), 0.01)
    }

    @Test
    fun `accumulates only ascents exceeding the 3m threshold`() {
        val climb = listOf(100.0, 104.0, 110.0, 108.0, 112.0, 116.0)
        // anchor 100 -> +4m @104, anchor 104 -> +6m @110, anchor 110 -> +6m @116
        assertEquals(16.0, ElevationGainCalculator.calculateAscent(climb), 0.01)
    }

    @Test
    fun `returns zero for a pure descent`() {
        val descent = listOf(200.0, 180.0, 150.0, 120.0)
        assertEquals(0.0, ElevationGainCalculator.calculateAscent(descent), 0.01)
    }

    @Test
    fun `handles empty and single element lists`() {
        assertEquals(0.0, ElevationGainCalculator.calculateAscent(emptyList()), 0.01)
        assertEquals(0.0, ElevationGainCalculator.calculateAscent(listOf(120.0)), 0.01)
    }
}
