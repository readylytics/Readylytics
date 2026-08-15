package app.readylytics.health.domain.util

import app.readylytics.health.domain.model.WorkoutRoutePoint
import kotlin.math.PI
import kotlin.math.cos

data class ProjectedPoint(
    val x: Float,
    val y: Float,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val timestampMs: Long,
)

data class RouteProjectionResult(
    val points: List<ProjectedPoint>,
    val widthMeters: Double,
    val heightMeters: Double,
    val centerLatitude: Double,
    val centerLongitude: Double,
)

object RouteProjector {
    private const val EARTH_RADIUS_METERS = 6_371_000.0
    private const val DEG_TO_RAD = PI / 180.0

    fun project(points: List<WorkoutRoutePoint>): RouteProjectionResult {
        if (points.isEmpty()) {
            return RouteProjectionResult(emptyList(), 0.0, 0.0, 0.0, 0.0)
        }

        val minLat = points.minOf { it.latitude }
        val maxLat = points.maxOf { it.latitude }
        val minLon = points.minOf { it.longitude }
        val maxLon = points.maxOf { it.longitude }
        val centerLat = (minLat + maxLat) / 2.0
        val centerLon = (minLon + maxLon) / 2.0

        val easting = points.map { point ->
            (point.longitude - centerLon) * cos(centerLat * DEG_TO_RAD) * DEG_TO_RAD * EARTH_RADIUS_METERS
        }
        val northing = points.map { point ->
            (point.latitude - centerLat) * DEG_TO_RAD * EARTH_RADIUS_METERS
        }

        val minX = easting.minOrNull()!!
        val maxX = easting.maxOrNull()!!
        val minY = northing.minOrNull()!!
        val maxY = northing.maxOrNull()!!
        val rangeX = maxX - minX
        val rangeY = maxY - minY
        val scale = maxOf(rangeX, rangeY)
        val degenerate = scale <= 0.0

        val projected = points.mapIndexed { index, point ->
            ProjectedPoint(
                x = if (degenerate) 0.5f else ((easting[index] - minX) / scale).toFloat(),
                y = if (degenerate) 0.5f else ((maxY - northing[index]) / scale).toFloat(),
                latitude = point.latitude,
                longitude = point.longitude,
                altitudeMeters = point.altitude,
                timestampMs = point.timestampMs,
            )
        }

        return RouteProjectionResult(
            points = projected,
            widthMeters = rangeX,
            heightMeters = rangeY,
            centerLatitude = centerLat,
            centerLongitude = centerLon,
        )
    }
}
