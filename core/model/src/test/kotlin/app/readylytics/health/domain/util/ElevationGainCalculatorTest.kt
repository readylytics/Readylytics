package app.readylytics.health.domain.util

import org.junit.Test
import kotlin.test.assertEquals

class ElevationGainCalculatorTest {

    @Test
    fun `filters out sub-threshold noise jitter`() {
        val jittery = listOf(100.0, 100.8, 99.5, 101.2, 100.1, 102.0, 99.8)
        assertEquals(0.0, ElevationGainCalculator.calculateAscent(jittery), 0.01)
    }

    @Test
    fun `accumulates only ascents exceeding the 3m threshold`() {
        val climb = listOf(100.0, 104.0, 110.0, 108.0, 112.0, 116.0)
        // anchor 100 -> +4m @104, anchor 104 -> +6m @110, drop to 108 (anchor resets) -> +4m @112, anchor 112 -> +4m @116
        assertEquals(18.0, ElevationGainCalculator.calculateAscent(climb), 0.01)
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

    @Test
    fun calculateAscent_tracksAscentAfterDescent() {
        // Starts at 200m, descends to 100m, climbs to 150m (50m gain), descends to 80m, climbs to 120m (40m gain) -> total 90m
        val altitudes = listOf(200.0, 150.0, 100.0, 110.0, 150.0, 80.0, 120.0)
        val gain = ElevationGainCalculator.calculateAscent(altitudes, thresholdMeters = 3.0)
        assertEquals(90.0, gain, 0.01)
    }

    @Test
    fun calculateAscent_filtersNoiseUnderThreshold() {
        // 100 -> 102 -> 101 -> 102 -> 100 (jitter within 2m, no true climb > 3m)
        val altitudes = listOf(100.0, 102.0, 101.0, 102.0, 100.0)
        val gain = ElevationGainCalculator.calculateAscent(altitudes, thresholdMeters = 3.0)
        assertEquals(0.0, gain, 0.01)
    }
}

