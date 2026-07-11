package app.readylytics.health.domain.util

import kotlin.math.cos

data class ProjectedPoint(
    val x: Double,
    val y: Double,
    val altitude: Double?,
    val timestampMs: Long
)

object RouteProjector {
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    fun project(latitudes: DoubleArray, longitudes: DoubleArray, altitudes: DoubleArray?, timestamps: LongArray): List<ProjectedPoint> {
        require(latitudes.size == longitudes.size && latitudes.size == timestamps.size) { "Input arrays must have the same size" }
        if (latitudes.isEmpty()) return emptyList()
        val latCenter = latitudes.average()
        val radLatCenter = Math.toRadians(latCenter)
        val cosLat = cos(radLatCenter)

        return latitudes.indices.map { i ->
            ProjectedPoint(
                x = EARTH_RADIUS_METERS * Math.toRadians(longitudes[i]) * cosLat,
                y = EARTH_RADIUS_METERS * Math.toRadians(latitudes[i]),
                altitude = altitudes?.getOrNull(i),
                timestampMs = timestamps[i]
            )
        }
    }
}
