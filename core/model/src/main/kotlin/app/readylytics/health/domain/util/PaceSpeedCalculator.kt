package app.readylytics.health.domain.util

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.atan2
import kotlin.math.sqrt

object PaceSpeedCalculator {
    private const val EARTH_RADIUS_M = 6371000.0

    // Returns cumulative distances in meters for a path of lat/lons
    fun calculateCumulativeDistances(latitudes: DoubleArray, longitudes: DoubleArray): DoubleArray {
        require(latitudes.size == longitudes.size) { "Input arrays must have the same size" }
        if (latitudes.isEmpty()) return DoubleArray(0)
        if (latitudes.size == 1) return DoubleArray(1) { 0.0 }
        val distances = DoubleArray(latitudes.size)
        distances[0] = 0.0
        var acc = 0.0
        for (i in 1..latitudes.lastIndex) {
            acc += haversineDistance(latitudes[i - 1], longitudes[i - 1], latitudes[i], longitudes[i])
            distances[i] = acc
        }
        return distances
    }

    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_M * c
    }
}
