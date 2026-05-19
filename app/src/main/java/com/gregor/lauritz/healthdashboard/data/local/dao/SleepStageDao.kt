package com.gregor.lauritz.healthdashboard.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepStageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SleepStageDao {
    @Upsert
    suspend fun upsertAll(stages: List<SleepStageEntity>)

    @Query("SELECT * FROM sleep_stages WHERE sessionId = :sessionId ORDER BY startTime ASC")
    fun observeStagesForSession(sessionId: String): Flow<List<SleepStageEntity>>

    @Query("DELETE FROM sleep_stages WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: String): Int

    @Query("DELETE FROM sleep_stages WHERE sessionId IN (:sessionIds)")
    suspend fun deleteForSessions(sessionIds: List<String>): Int
}
