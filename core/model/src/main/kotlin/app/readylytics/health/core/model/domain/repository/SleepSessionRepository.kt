package app.readylytics.health.core.model.domain.repository

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
    val sessionId: String? = null,
) {
    fun getStartOffsetMinutes(sessionStartTime: Long): Int = ((startTime - sessionStartTime) / 60_000L).toInt()
}

interface SleepSessionRepository {
    fun observeSince(fromMs: Long): Flow<List<SleepSessionData>>

    suspend fun getSince(fromMs: Long): List<SleepSessionData>

    /**
     * Sessions that both start at/after [fromMs] and end at/before [toMs], oldest first.
     *
     * The bounded counterpart of [getSince]. Callers that only need a fixed trailing window (the
     * morning-recommendation assembly needs the ~60 days ending at the day it is scoring) must use
     * this: [getSince] materializes every row through the newest one in the table, so replaying N
     * historical days with it costs O(N^2) rows instead of O(N).
     */
    suspend fun getInRange(
        fromMs: Long,
        toMs: Long,
    ): List<SleepSessionData>

    suspend fun countSince(fromMs: Long): Int

    fun observeSessionStages(sessionId: String): Flow<List<SleepStageData>>

    suspend fun getSessionStages(sessionId: String): List<SleepStageData>

    /**
     * WP-14/C4: bounded multi-ID counterpart of [getSessionStages], for fetching exactly a core
     * cluster's canonical segment IDs' stages in one query (deduplicated/ordered by the caller).
     */
    suspend fun getSessionStages(sessionIds: List<String>): List<SleepStageData>

    fun observeFirstSessionEndingInRange(
        fromMs: Long,
        toMs: Long,
    ): Flow<SleepSessionData?>
}
