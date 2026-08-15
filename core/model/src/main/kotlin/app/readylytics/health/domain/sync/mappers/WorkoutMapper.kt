package app.readylytics.health.domain.sync.mappers

import app.readylytics.health.domain.model.DomainExerciseSessionRecord
import app.readylytics.health.domain.model.DomainRouteLocation
import app.readylytics.health.domain.model.WorkoutRoutePoint
import app.readylytics.health.domain.sync.WorkoutInput
import app.readylytics.health.domain.util.RouteDistanceCalculator

object WorkoutMapper {
    private const val ELEVATION_GAIN_THRESHOLD_METERS = 3.0

    fun mapExerciseSession(session: DomainExerciseSessionRecord): WorkoutInput {
        val durationMinutes = ((session.endTime.toEpochMilli() - session.startTime.toEpochMilli()) / 60_000L).toInt()
        return WorkoutInput(
            id = session.id,
            startTime = session.startTime.toEpochMilli(),
            endTime = session.endTime.toEpochMilli(),
            exerciseType = session.exerciseType,
            durationMinutes = durationMinutes,
            zone1Minutes = 0f,
            zone2Minutes = 0f,
            zone3Minutes = 0f,
            zone4Minutes = 0f,
            zone5Minutes = 0f,
            trimp = 0f,
            avgHr = 0f,
            deviceName = session.deviceName,
            routePoints = session.routePoints.map { it.toRoutePoint(session.id) },
            totalDistanceMeters =
                session.totalDistanceMeters?.toFloat()
                    ?: fallbackDistanceMeters(session.routePoints),
            avgSpeedKmh =
                session.avgSpeedMps?.let { (it * 3.6).toFloat() }
                    ?: fallbackAvgSpeedKmh(session.routePoints, session.startTime.toEpochMilli(), session.endTime.toEpochMilli()),
            elevationGainMeters =
                session.elevationGainMeters?.toFloat()
                    ?: fallbackElevationGainMeters(session.routePoints),
            routeState = session.routeState,
        )
    }

    private fun fallbackDistanceMeters(points: List<DomainRouteLocation>): Float? {
        val distance = RouteDistanceCalculator.pathDistanceMeters(points)
        return distance.takeIf { it > 0.0 }?.toFloat()
    }

    private fun fallbackAvgSpeedKmh(
        points: List<DomainRouteLocation>,
        startMs: Long,
        endMs: Long,
    ): Float? {
        val distanceMeters = RouteDistanceCalculator.pathDistanceMeters(points)
        val durationSeconds = (endMs - startMs) / 1000.0
        if (distanceMeters <= 0.0 || durationSeconds <= 0.0) return null
        return (distanceMeters / durationSeconds * 3.6).toFloat()
    }

    private fun fallbackElevationGainMeters(points: List<DomainRouteLocation>): Float? {
        var gain = 0.0
        var anchor: Double? = null
        for (point in points) {
            val altitude = point.altitudeMeters ?: continue
            val previous = anchor ?: run {
                anchor = altitude
                continue
            }
            if (altitude - previous >= ELEVATION_GAIN_THRESHOLD_METERS) {
                gain += altitude - previous
                anchor = altitude
            }
        }
        return gain.takeIf { it > 0.0 }?.toFloat()
    }
}

private fun DomainRouteLocation.toRoutePoint(workoutId: String) =
    WorkoutRoutePoint(
        workoutId = workoutId,
        latitude = latitude,
        longitude = longitude,
        altitude = altitudeMeters,
        timestampMs = time.toEpochMilli(),
        horizontalAccuracy = horizontalAccuracyMeters,
        verticalAccuracy = verticalAccuracyMeters,
    )
