package app.readylytics.health.core.model.domain.util

import app.readylytics.health.domain.model.WorkoutRoutePoint
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouteProjectorTest {
    private fun point(
        lat: Double,
        lon: Double,
        altitude: Double? = null,
        timestampMs: Long = 0L,
    ) = WorkoutRoutePoint(
        id = 0,
        workoutId = "workout-1",
        latitude = lat,
        longitude = lon,
        altitude = altitude,
        timestampMs = timestampMs,
    )

    @Test
    fun `projects coordinates into the unit box preserving aspect`() {
        val path =
            listOf(
                point(48.8566, 2.3522),
                point(48.8570, 2.3530),
                point(48.8580, 2.3545),
                point(48.8590, 2.3535),
            )
        val result = RouteProjector.project(path)
        assertEquals(4, result.points.size)
        result.points.forEach { p ->
            assertTrue(p.x in 0f..1f, "x out of range: ${p.x}")
            assertTrue(p.y in 0f..1f, "y out of range: ${p.y}")
        }
        val xRange = result.points.maxOf { it.x } - result.points.minOf { it.x }
        val yRange = result.points.maxOf { it.y } - result.points.minOf { it.y }
        assertTrue(xRange > 0f && yRange > 0f)
        assertEquals(1f, maxOf(xRange, yRange), 0.001f)
        assertTrue(result.widthMeters > 0.0)
        assertTrue(result.heightMeters > 0.0)
    }

    @Test
    fun `northerly points have smaller y than southerly points`() {
        val northPoint = point(60.0, 10.0)
        val southPoint = point(50.0, 10.0)
        val result = RouteProjector.project(listOf(northPoint, southPoint))
        assertEquals(2, result.points.size)
        assertEquals(0f, result.points[0].y, 0.001f)
        assertEquals(1f, result.points[1].y, 0.001f)
    }

    @Test
    fun `cosine latitude scaling narrows the horizontal span at high latitude`() {
        val equator = RouteProjector.project(listOf(point(0.0, 0.0), point(0.0, 1.0)))
        val at60 = RouteProjector.project(listOf(point(60.0, 0.0), point(60.0, 1.0)))
        val expectedEquator = PI / 180.0 * 6_371_000.0
        assertEquals(expectedEquator, equator.widthMeters, 1.0)
        assertEquals(expectedEquator * cos(PI / 3.0), at60.widthMeters, 1.0)
        assertEquals(0.0, at60.heightMeters, 0.001)
    }

    @Test
    fun `single point projects to the center with zero span`() {
        val result = RouteProjector.project(listOf(point(10.0, 20.0)))
        assertEquals(1, result.points.size)
        assertEquals(0.5f, result.points[0].x, 0.001f)
        assertEquals(0.5f, result.points[0].y, 0.001f)
        assertEquals(0.0, result.widthMeters, 0.001)
        assertEquals(0.0, result.heightMeters, 0.001)
    }

    @Test
    fun `empty list produces an empty projection`() {
        val result = RouteProjector.project(emptyList())
        assertTrue(result.points.isEmpty())
        assertEquals(0.0, result.widthMeters, 0.001)
        assertEquals(0.0, result.heightMeters, 0.001)
    }
}
