package app.readylytics.health.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaceSpeedCalculatorTest {
    @Test
    fun testEmptyInputReturnsZeros() {
        val dists = PaceSpeedCalculator.calculateCumulativeDistances(DoubleArray(0), DoubleArray(0))
        assertEquals(0, dists.size)
    }

    @Test
    fun testSinglePointReturnsZero() {
        val dists = PaceSpeedCalculator.calculateCumulativeDistances(doubleArrayOf(45.0), doubleArrayOf(90.0))
        assertEquals(1, dists.size)
        assertEquals(0.0, dists[0], 0.001)
    }

    @Test
    fun testHaversineDistanceAccumulation() {
        val lats = doubleArrayOf(0.0, 1.0)
        val lons = doubleArrayOf(0.0, 0.0)
        
        val result = PaceSpeedCalculator.calculateCumulativeDistances(lats, lons)
        assertEquals(2, result.size)
        assertEquals(0.0, result[0], 0.001)
        
        val expectedDist = 6371000.0 * Math.toRadians(1.0)
        assertEquals(expectedDist, result[1], 1.0)
    }
}
