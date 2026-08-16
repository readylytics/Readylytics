package app.readylytics.health.feature.workouts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scale bar is rendered as a fraction of the drawn contour, so the picked step must always
 * land in a readable band of that contour -- a legend whose length does not match its label is
 * worse than no legend.
 */
class ScaleBarLadderTest {
    @Test
    fun `picks the largest one-two-five step that fits half the route`() {
        assertEquals(5_000.0, pickScaleBarMeters(10_000.0), 0.0)
        assertEquals(5_000.0, pickScaleBarMeters(19_999.0), 0.0)
        assertEquals(10_000.0, pickScaleBarMeters(20_000.0), 0.0)
        assertEquals(500.0, pickScaleBarMeters(1_000.0), 0.0)
        assertEquals(100.0, pickScaleBarMeters(250.0), 0.0)
    }

    @Test
    fun `bar occupies between 20 and 50 percent of the contour across four orders of magnitude`() {
        var dimension = 100.0
        while (dimension <= 400_000.0) {
            val fraction = pickScaleBarMeters(dimension) / dimension
            assertTrue(
                "maxDimension=$dimension produced fraction=$fraction",
                fraction in 0.2..0.5,
            )
            dimension *= 1.37
        }
    }

    @Test
    fun `routes smaller than the shortest step fall back to that step`() {
        assertEquals(10.0, pickScaleBarMeters(5.0), 0.0)
        assertEquals(10.0, pickScaleBarMeters(0.5), 0.0)
    }
}
