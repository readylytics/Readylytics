package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.core.model.domain.model.RouteState
import app.readylytics.health.core.model.domain.model.WorkoutRoutePoint
import app.readylytics.health.core.model.domain.repository.ReadOutcome
import app.readylytics.health.core.model.domain.repository.map
import app.readylytics.health.core.model.domain.sync.PreparedWorkout
import app.readylytics.health.core.model.domain.sync.mergeEnrichment

/**
 * Resolves [PreparedWorkout.route]/[PreparedWorkout.distanceMeters]/[PreparedWorkout.elevationMeters]
 * against [existing] via [mergeEnrichment]: an Available outcome (including an authoritatively
 * empty/null value) replaces, Denied/Unsupported preserves the stored value. `avgSpeedKmh` is
 * always re-derived from the *selected* distance and this workout's own duration, and cleared
 * when that distance is authoritatively absent -- never preserved independently of distance.
 */
fun PreparedWorkout.toMergedEntity(existing: WorkoutRecordEntity?): WorkoutRecordEntity {
    val distanceMeters = mergeEnrichment(existing?.totalDistanceMeters, this.distanceMeters)
    val elevationGainMeters = mergeEnrichment(existing?.elevationGainMeters, elevationMeters)
    val routeState =
        mergeEnrichment(
            existing?.routeState ?: RouteState.NOT_AVAILABLE,
            route.map { points -> if (points.isNotEmpty()) RouteState.IMPORTED else RouteState.NOT_AVAILABLE },
        )
    val avgSpeedKmh = deriveWorkoutAvgSpeedKmh(distanceMeters, workout.startTime, workout.endTime)
    return workout.toEntity().copy(
        modelTrimp = existing?.modelTrimp,
        modelTrimpSourceRevision = existing?.modelTrimpSourceRevision,
        modelTrimpSnapshotId = existing?.modelTrimpSnapshotId,
        modelTrimpAlgorithmRevision = existing?.modelTrimpAlgorithmRevision,
        modelTrimpQuality = existing?.modelTrimpQuality,
        totalDistanceMeters = distanceMeters,
        avgSpeedKmh = avgSpeedKmh,
        elevationGainMeters = elevationGainMeters,
        routeState = routeState,
    )
}

/**
 * Touches `workout_route_points` only when [PreparedWorkout.route] is [ReadOutcome.Available] --
 * a Denied/Unsupported read must never delete an already-imported route it merely couldn't
 * re-read this round (H5/WP-09).
 */
suspend fun applyRoutePoints(daos: HealthRecordDaos, prepared: PreparedWorkout) {
    val route = prepared.route
    if (route !is ReadOutcome.Available) return
    daos.workoutRoutePointDao.deleteForWorkouts(listOf(prepared.workout.id))
    if (route.data.isNotEmpty()) {
        daos.workoutRoutePointDao.insertAll(route.data.map(WorkoutRoutePoint::toEntity))
    }
}
