package app.readylytics.health.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteProjectorTest {
    @Test
    fun testEmptyInputReturnsEmptyList() {
        val result = RouteProjector.project(DoubleArray(0), DoubleArray(0), null, LongArray(0))
        assertTrue(result.isEmpty())
    }

    @Test
    fun testProjectionOfPoints() {
        val latitudes = doubleArrayOf(45.0, 46.0)
        val longitudes = doubleArrayOf(90.0, 91.0)
        val altitudes = doubleArrayOf(100.0, 200.0)
        val timestamps = longArrayOf(1000L, 2000L)

        val result = RouteProjector.project(latitudes, longitudes, altitudes, timestamps)
        
        assertEquals(2, result.size)
        val p1 = result[0]
        val latCenter = 45.5
        val radLatCenter = Math.toRadians(latCenter)
        val cosLat = Math.cos(radLatCenter)
        
        assertEquals(90.0 * cosLat, p1.x, 0.00001)
        assertEquals(45.0, p1.y, 0.00001)
        assertEquals(100.0, p1.altitude ?: 0.0, 0.00001)
        assertEquals(1000L, p1.timestampMs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testProjectThrowsOnMismatchedArraySizes() {
        RouteProjector.project(
            latitudes = doubleArrayOf(45.0, 46.0),
            longitudes = doubleArrayOf(90.0), // mismatched
            altitudes = null,
            timestamps = longArrayOf(1000L, 2000L)
        )
    }
}
