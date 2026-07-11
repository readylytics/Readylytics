package app.readylytics.health.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSimplifierTest {
    @Test
    fun testSimplifyFewerPointsThanMax() {
        val points = listOf(
            ProjectedPoint(0.0, 0.0, 100.0, 1000L),
            ProjectedPoint(1.0, 1.0, 100.0, 2000L)
        )
        val result = RouteSimplifier.simplify(points, 5)
        assertEquals(points, result)
    }

    @Test
    fun testSimplifyDouglasPeucker() {
        val points = listOf(
            ProjectedPoint(0.0, 0.0, 100.0, 1000L),
            ProjectedPoint(1.0, 1.0, 100.0, 2000L),
            ProjectedPoint(2.0, 2.0, 100.0, 3000L),
            ProjectedPoint(3.0, 1.0, 100.0, 4000L),
            ProjectedPoint(4.0, 0.0, 100.0, 5000L)
        )
        val result = RouteSimplifier.simplify(points, 3, 0.1)
        assertEquals(3, result.size)
        assertEquals(0.0, result[0].x, 0.001)
        assertEquals(2.0, result[1].x, 0.001)
        assertEquals(4.0, result[2].x, 0.001)
    }

    @Test
    fun testSimplifyFallbackDownsampling() {
        val points = (0..9).map { i ->
            ProjectedPoint(i.toDouble(), if (i % 2 == 0) 1.0 else 0.0, 100.0, i * 1000L)
        }
        val result = RouteSimplifier.simplify(points, 4, tolerance = 0.0)
        assertEquals(4, result.size)
    }
}
