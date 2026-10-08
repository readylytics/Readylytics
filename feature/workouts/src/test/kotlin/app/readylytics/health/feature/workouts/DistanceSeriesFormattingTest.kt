package app.readylytics.health.feature.workouts

import app.readylytics.health.core.model.domain.preferences.UnitSystem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

class DistanceSeriesFormattingTest {
    private lateinit var savedLocale: Locale

    @Before
    fun pinLocale() {
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(savedLocale)
    }

    @Test
    fun `metric series drops non-finite values and rounds distance to metres`() {
        val raw = listOf(0.0 to 5.5, 1.2 to Double.NaN, 1.5 to Double.POSITIVE_INFINITY, 1.0004 to 5.2)

        val display = toDisplaySeries(raw, DistanceSeriesKind.PACE, UnitSystem.METRIC)

        assertEquals(listOf(0.0 to 5.5, 1.0 to 5.2), display)
    }

    @Test
    fun `imperial pace converts min per km to min per mile`() {
        val (distance, pace) =
            toDisplaySeries(listOf(1.0 to 5.0), DistanceSeriesKind.PACE, UnitSystem.IMPERIAL).single()

        assertEquals(0.621, distance, 1e-9)
        assertEquals(5.0 * 1.609344, pace, 1e-5)
    }

    @Test
    fun `imperial speed converts km per hour to miles per hour`() {
        val (distance, speed) =
            toDisplaySeries(listOf(1.0 to 10.0), DistanceSeriesKind.SPEED, UnitSystem.IMPERIAL).single()

        assertEquals(0.621, distance, 1e-9)
        assertEquals(6.21371, speed, 1e-5)
    }

    @Test
    fun `imperial elevation converts metres to feet`() {
        val (_, altitude) =
            toDisplaySeries(listOf(1.0 to 100.0), DistanceSeriesKind.ELEVATION, UnitSystem.IMPERIAL).single()

        assertEquals(328.084, altitude, 1e-3)
    }

    @Test
    fun `pace renders minutes and seconds and never shows sixty seconds`() {
        assertEquals("5:30", formatSeriesValue(DistanceSeriesKind.PACE, 5.5))
        assertEquals("5:59", formatSeriesValue(DistanceSeriesKind.PACE, 5.999))
    }

    @Test
    fun `speed keeps one decimal and elevation none`() {
        assertEquals("12.3", formatSeriesValue(DistanceSeriesKind.SPEED, 12.34))
        assertEquals("100", formatSeriesValue(DistanceSeriesKind.ELEVATION, 99.6))
    }

    @Test
    fun `point text appends the unit and distance keeps two decimals`() {
        assertEquals("5:30 min/km", formatPointValue(DistanceSeriesKind.PACE, 5.5, "min/km"))
        assertEquals("1.23 km", formatPointDistance(1.23456, "km"))
    }

    @Test
    fun `distance ticks switch to whole numbers from ten units`() {
        assertEquals("2.0", formatDistanceTick(2.0, maxDistance = 9.9))
        assertEquals("12", formatDistanceTick(12.0, maxDistance = 10.0))
    }

    @Test
    fun `y range pads by the kind margin and floors at zero`() {
        val pace = seriesYRange(listOf(5.0, 6.0), DistanceSeriesKind.PACE.yMargin)
        assertEquals(4.5, pace.start, 1e-9)
        assertEquals(6.5, pace.endInclusive, 1e-9)

        val floored = seriesYRange(listOf(0.2), DistanceSeriesKind.SPEED.yMargin)
        assertEquals(0.0, floored.start, 1e-9)
        assertEquals(1.2, floored.endInclusive, 1e-9)

        val empty = seriesYRange(emptyList(), DistanceSeriesKind.ELEVATION.yMargin)
        assertEquals(0.0, empty.start, 1e-9)
        assertEquals(5.0, empty.endInclusive, 1e-9)
    }

    @Test
    fun `distance labels split the range into four intervals`() {
        assertEquals(listOf(0.0), computeDistanceLabels(0.0))
        assertEquals(listOf(0.0, 2.0, 4.0, 6.0, 8.0), computeDistanceLabels(8.0))
    }

    @Test
    fun `nearest point picks the closest x and defaults to zero when empty`() {
        val points = listOf(0.0 to 1.0, 1.0 to 1.0, 2.0 to 1.0)
        assertEquals(1, nearestPointIndex(points, 1.4))
        assertEquals(0, nearestPointIndex(emptyList(), 1.4))
    }

    @Test
    fun `accessibility traversal wraps in both directions`() {
        assertEquals(4, previousPointIndex(current = null, lastIndex = 4))
        assertEquals(4, previousPointIndex(current = 0, lastIndex = 4))
        assertEquals(2, previousPointIndex(current = 3, lastIndex = 4))
        assertEquals(0, nextPointIndex(current = null, lastIndex = 4))
        assertEquals(0, nextPointIndex(current = 4, lastIndex = 4))
        assertEquals(3, nextPointIndex(current = 2, lastIndex = 4))
    }
}
