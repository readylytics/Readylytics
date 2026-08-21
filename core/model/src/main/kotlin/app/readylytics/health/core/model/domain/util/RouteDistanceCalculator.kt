package app.readylytics.health.core.model.domain.util

import app.readylytics.health.core.model.domain.model.DomainRouteLocation
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object RouteDistanceCalculator {

    private const val EARTH_RADIUS_METERS = 6_371_000.0

    fun haversineMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
    ): Double {
        val dLat = (lat2 - lat1) * PI / 180.0
        val dLon = (lon2 - lon1) * PI / 180.0
        val a =
            sin(dLat / 2.0) * sin(dLat / 2.0) +
                cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) * sin(dLon / 2.0) * sin(dLon / 2.0)
        return EARTH_RADIUS_METERS * 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
    }

    fun pathDistanceMeters(route: List<DomainRouteLocation>): Double =
        route
            .zipWithNext()
            .sumOf { (a, b) ->
                haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude)
            }
}
