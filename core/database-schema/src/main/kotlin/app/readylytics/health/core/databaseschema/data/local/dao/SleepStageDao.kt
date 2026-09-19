package app.readylytics.health.core.databaseschema.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.readylytics.health.core.databaseschema.data.local.entity.SleepStageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SleepStageDao {
    @Upsert
    suspend fun upsertAll(stages: List<SleepStageEntity>)

    @Query("SELECT * FROM sleep_stages WHERE sessionId = :sessionId ORDER BY startTime ASC")
    fun observeStagesForSession(sessionId: String): Flow<List<SleepStageEntity>>

    @Query("SELECT * FROM sleep_stages WHERE sessionId = :sessionId ORDER BY startTime ASC")
    suspend fun getStagesForSession(sessionId: String): List<SleepStageEntity>

    /**
     * WP-14/C4: bounded multi-ID counterpart of [getStagesForSession], used to fetch a core
     * cluster's canonical segment IDs' stages in one query. [sessionIds] is expected to be the
     * small (typically single-digit) set of a core cluster's own segments, not an open-ended list.
     */
    @Query(
        "SELECT * FROM sleep_stages WHERE sessionId IN (:sessionIds) " +
            "ORDER BY startTime ASC, endTime ASC, sessionId ASC",
    )
    suspend fun getStagesForSessions(sessionIds: List<String>): List<SleepStageEntity>

    @Query("DELETE FROM sleep_stages WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: String): Int

    @Query("DELETE FROM sleep_stages WHERE sessionId IN (:sessionIds)")
    suspend fun deleteForSessions(sessionIds: List<String>): Int

    @Query("DELETE FROM sleep_stages")
    suspend fun deleteAll(): Int
}
