package app.readylytics.health.core.model.domain.repository

interface WorkoutRouteLookup {
    suspend fun snapshots(ids: List<String>): Map<String, StoredWorkoutRouteSnapshot>
}

data class StoredWorkoutRouteSnapshot(
    val id: String,
    val startTime: Long,
    val endTime: Long,
    val exerciseType: String,
    val deviceName: String?,
    val routeState: String,
)
