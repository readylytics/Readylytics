package app.readylytics.health.domain.util

import kotlin.math.cos

data class ProjectedPoint(
    val x: Double,
    val y: Double,
    val altitude: Double?,
    val timestampMs: Long
)

object RouteProjector {
    fun project(latitudes: DoubleArray, longitudes: DoubleArray, altitudes: DoubleArray?, timestamps: LongArray): List<ProjectedPoint> {
        if (latitudes.isEmpty()) return emptyList()
        val latCenter = latitudes.average()
        val radLatCenter = Math.toRadians(latCenter)
        val cosLat = cos(radLatCenter)

        return latitudes.indices.map { i ->
            ProjectedPoint(
                x = longitudes[i] * cosLat,
                y = latitudes[i],
                altitude = altitudes?.getOrNull(i),
                timestampMs = timestamps[i]
            )
        }
    }
}
