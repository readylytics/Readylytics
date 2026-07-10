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
)

interface WorkoutRepository {
    suspend fun getById(id: String): WorkoutData?

    suspend fun getEarliestWorkoutTimestamp(): Long?

    fun observeSince(fromMs: Long): Flow<List<WorkoutData>>
}
