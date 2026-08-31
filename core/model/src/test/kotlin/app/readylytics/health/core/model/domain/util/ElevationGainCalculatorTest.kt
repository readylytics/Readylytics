package app.readylytics.health.core.model.domain.util

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ElevationGainCalculatorTest {

    @Test
    fun `filters out sub-threshold noise jitter`() {
        val jittery = listOf(100.0, 100.8, 99.5, 101.2, 100.1, 102.0, 99.8)
        assertEquals(0.0, ElevationGainCalculator.calculateAscent(jittery), 0.01)
    }

    @Test
    fun `accumulates net climb ignoring sub-threshold noise dips`() {
        val climb = listOf(100.0, 104.0, 110.0, 108.0, 112.0, 116.0)
        // 100 -> 110 (+10m climb), 2m noise dip to 108 (< 3m), climb continues to 116 (+6m) -> net 16m
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

    @Test
    fun calculateAscent_tracksAscentAfterDescent() {
        // Starts at 200m, descends to 100m, climbs to 150m (50m gain),
        // descends to 80m, climbs to 120m (40m gain) -> total 90m
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

    @Test
    fun calculateAscent_filtersOutlierSpikes() {
        // Corrupted GPS telemetry spike: 100 -> 1,000,000 -> 105 -> 110
        val altitudes = listOf(100.0, 1_000_000.0, 105.0, 110.0)
        val gain = ElevationGainCalculator.calculateAscent(altitudes, thresholdMeters = 3.0)
        assertEquals(10.0, gain, 0.01)
    }

    @Test
    fun isValidAltitude_rejectsNonFiniteAndOutOfRangeValues() {
        assertFalse(ElevationGainCalculator.isValidAltitude(Double.NaN))
        assertFalse(ElevationGainCalculator.isValidAltitude(Double.POSITIVE_INFINITY))
        assertFalse(ElevationGainCalculator.isValidAltitude(Double.NEGATIVE_INFINITY))
        assertFalse(ElevationGainCalculator.isValidAltitude(-501.0))
        assertFalse(ElevationGainCalculator.isValidAltitude(9001.0))
        assertTrue(ElevationGainCalculator.isValidAltitude(-500.0))
        assertTrue(ElevationGainCalculator.isValidAltitude(0.0))
        assertTrue(ElevationGainCalculator.isValidAltitude(9000.0))
    }

    @Test
    fun filterAltitudePlaceholders_dropsZeroPlaceholdersWhenRouteHasRealTerrain() {
        // Health Connect reports 0.0 for points without an altitude reading; a route with
        // genuinely elevated terrain cannot also repeatedly sit at exactly sea level.
        val placeholders = listOf(0.0, 0.0, 270.0, 0.0, 275.0, 0.0, 0.0, 280.0, 0.0)
        assertEquals(
            listOf(270.0, 275.0, 280.0),
            ElevationGainCalculator.filterAltitudePlaceholders(placeholders),
        )
    }

    @Test
    fun filterAltitudePlaceholders_keepsZerosForFlatRoutes() {
        val flat = listOf(0.0, 0.0, 1.0, 0.0, 0.0)
        assertEquals(flat, ElevationGainCalculator.filterAltitudePlaceholders(flat))
    }

    @Test
    fun calculateAscent_ignoresZeroPlaceholdersWhenRouteHasRealTerrain() {
        // Without the placeholder filter these repeated 0 -> ~270m jumps would sum to a huge fake gain.
        val altitudes = listOf(0.0, 0.0, 270.0, 0.0, 275.0, 0.0, 0.0, 280.0, 0.0)
        val gain = ElevationGainCalculator.calculateAscent(altitudes, thresholdMeters = 3.0)
        assertEquals(10.0, gain, 0.01)
    }

    @Test
    fun smoothElevationProfile_interpolatesPlaceholdersBetweenRealReadings() {
        val series =
            listOf(
                0.0 to 0.0,
                1.0 to null,
                2.0 to 270.0,
                3.0 to 0.0,
                4.0 to 280.0,
                5.0 to null,
            )
        val smoothed = ElevationGainCalculator.smoothElevationProfile(series)
        assertEquals(
            listOf(
                2.0 to 270.0,
                3.0 to 275.0,
                4.0 to 280.0,
            ),
            smoothed,
        )
    }

    @Test
    fun smoothElevationProfile_dropsLeadingAndTrailingPlaceholders() {
        val series =
            listOf(
                0.0 to 0.0,
                1.0 to 0.0,
                2.0 to 270.0,
                3.0 to 0.0,
                4.0 to 0.0,
            )
        val smoothed = ElevationGainCalculator.smoothElevationProfile(series)
        assertEquals(listOf(2.0 to 270.0), smoothed)
    }

    @Test
    fun smoothElevationProfile_keepsFlatRouteZerosAsReal() {
        val series = listOf(0.0 to 0.0, 1.0 to 0.0, 2.0 to 1.0, 3.0 to 0.0)
        val smoothed = ElevationGainCalculator.smoothElevationProfile(series)
        assertEquals(series, smoothed)
    }

    @Test
    fun smoothElevationProfile_skipsInterpolationWhenRealReadingsShareDistance() {
        // Rounding cumulative distance to km can put two real readings at the same x; the
        // interpolant would divide by zero and emit Infinity, which crashes the chart.
        val series = listOf(1.2 to 270.0, 1.2 to null, 1.2 to 280.0)
        val smoothed = ElevationGainCalculator.smoothElevationProfile(series)
        assertEquals(listOf(1.2 to 270.0, 1.2 to 280.0), smoothed)
    }
}
