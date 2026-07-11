package app.readylytics.health.domain.repository

import kotlinx.coroutines.flow.Flow

data class WorkoutData(
    val id: String,
    val startTime: Long,
    val endTime: Long,
    val exerciseType: String,
    val durationMinutes: Int,
    val zone1Minutes: Float,
    val zone2Minutes: Float,
    val zone3Minutes: Float,
    val zone4Minutes: Float,
    val zone5Minutes: Float,
    val trimp: Float,
    val avgHr: Float,
    val deviceName: String? = null,
    val routeState: String = "NOT_AVAILABLE",
    val avgSpeedKmh: Float? = null,
    val avgPaceMinKm: Float? = null,
    val elevationGainMeters: Float? = null,
    val totalDistanceMeters: Float? = null
)

data class RoutePoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val timestampMs: Long
)

interface WorkoutRepository {
    suspend fun getById(id: String): WorkoutData?
    suspend fun getEarliestWorkoutTimestamp(): Long?
    fun observeSince(fromMs: Long): Flow<List<WorkoutData>>

    suspend fun getRoutePoints(workoutId: String): List<RoutePoint>
    suspend fun updateRouteState(workoutId: String, routeState: String)
    suspend fun saveRoutePoints(workoutId: String, points: List<RoutePoint>, stats: WorkoutStats)
}

data class WorkoutStats(
    val avgSpeedKmh: Float?,
    val avgPaceMinKm: Float?,
    val elevationGainMeters: Float?,
    val totalDistanceMeters: Float?
)
