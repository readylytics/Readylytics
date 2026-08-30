package app.readylytics.health.core.model.domain.repository

import app.readylytics.health.core.model.domain.model.RouteState
import app.readylytics.health.core.model.domain.model.WorkoutRoutePoint
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
    val totalDistanceMeters: Float? = null,
    val avgSpeedKmh: Float? = null,
    val elevationGainMeters: Float? = null,
    val routeState: String = RouteState.NOT_AVAILABLE,
)

interface WorkoutRepository {
    suspend fun getById(id: String): WorkoutData?

    suspend fun getEarliestWorkoutTimestamp(): Long?

    suspend fun getInRange(fromMs: Long, toMs: Long): List<WorkoutData>

    suspend fun getInRangePaged(
        fromMs: Long,
        toMs: Long,
        limit: Int,
        offset: Int,
    ): List<WorkoutData>

    suspend fun countByTimeRange(fromMs: Long, toMs: Long): Int

    suspend fun getCanonicalFatigueSeed(evalMs: Long): List<FatigueWorkoutInput>

    suspend fun getRoutePoints(workoutId: String): List<WorkoutRoutePoint>

    fun observeSince(fromMs: Long): Flow<List<WorkoutData>>
}
