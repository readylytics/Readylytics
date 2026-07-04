package app.readylytics.health.domain.repository

import kotlinx.coroutines.flow.Flow

data class SleepSessionData(
    val id: String,
    val deviceName: String?,
    val startTime: Long,
    val endTime: Long,
    val durationMinutes: Int,
    val efficiency: Float,
    val deepSleepMinutes: Int,
    val lightSleepMinutes: Int,
    val remSleepMinutes: Int,
    val awakeMinutes: Int,
    val sleepScore: Float? = null,
    val startZoneOffsetSeconds: Int? = null,
    val endZoneOffsetSeconds: Int? = null,
)

data class SleepStageData(
    val stageType: String,
    val startTime: Long,
    val endTime: Long,
    val durationMinutes: Int,
) {
    fun getStartOffsetMinutes(sessionStartTime: Long): Int = ((startTime - sessionStartTime) / 60_000L).toInt()
}

interface SleepSessionRepository {
    fun observeSince(fromMs: Long): Flow<List<SleepSessionData>>

    suspend fun getSince(fromMs: Long): List<SleepSessionData>

    suspend fun getPaged(
        fromMs: Long,
        limit: Int,
        offset: Int,
    ): List<SleepSessionData>

    suspend fun countSince(fromMs: Long): Int

    fun observeSessionStages(sessionId: String): Flow<List<SleepStageData>>

    fun observeFirstSessionEndingInRange(
        fromMs: Long,
        toMs: Long,
    ): Flow<SleepSessionData?>
}
