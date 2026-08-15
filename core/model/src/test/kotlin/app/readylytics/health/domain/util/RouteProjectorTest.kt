package app.readylytics.health.domain.util

import app.readylytics.health.data.local.entity.WorkoutRoutePointEntity
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouteProjectorTest {

    private fun entity(
        lat: Double,
        lon: Double,
        altitude: Double? = null,
        timestampMs: Long = 0L,
    ) = WorkoutRoutePointEntity(
        id = 0,
        workoutId = "workout-1",
        latitude = lat,
        longitude = lon,
        altitude = altitude,
        timestampMs = timestampMs,
    )

    @Test
    fun `projects coordinates into the unit box preserving aspect`() {
        val path = listOf(
            entity(48.8566, 2.3522),
            entity(48.8570, 2.3530),
            entity(48.8580, 2.3545),
            entity(48.8590, 2.3535),
        )
        val result = RouteProjector.project(path)
        assertEquals(4, result.points.size)
        result.points.forEach { point ->
            assertTrue(point.x in 0f..1f, "x out of range: ${point.x}")
            assertTrue(point.y in 0f..1f, "y out of range: ${point.y}")
        }
        val xRange = result.points.maxOf { it.x } - result.points.minOf { it.x }
        val yRange = result.points.maxOf { it.y } - result.points.minOf { it.y }
        assertTrue(xRange > 0f && yRange > 0f)
        assertEquals(1f, maxOf(xRange, yRange), 0.001f)
        assertTrue(result.widthMeters > 0.0)
        assertTrue(result.heightMeters > 0.0)
    }

    @Test
    fun `cosine latitude scaling narrows the horizontal span at high latitude`() {
        val equator = RouteProjector.project(listOf(entity(0.0, 0.0), entity(0.0, 1.0)))
        val at60 = RouteProjector.project(listOf(entity(60.0, 0.0), entity(60.0, 1.0)))
        val expectedEquator = PI / 180.0 * 6_371_000.0
        assertEquals(expectedEquator, equator.widthMeters, 1.0)
        assertEquals(expectedEquator * cos(PI / 3.0), at60.widthMeters, 1.0)
        assertEquals(0.0, at60.heightMeters, 0.001)
    }

    @Test
    fun `single point projects to the center with zero span`() {
        val result = RouteProjector.project(listOf(entity(10.0, 20.0)))
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
