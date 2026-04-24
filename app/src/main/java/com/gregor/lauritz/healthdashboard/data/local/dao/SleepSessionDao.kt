package com.gregor.lauritz.healthdashboard.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SleepSessionDao {
    @Query("SELECT * FROM sleep_sessions WHERE startTime >= :fromMs ORDER BY startTime DESC")
    fun observeSince(fromMs: Long): Flow<List<SleepSessionEntity>>

    @Query("SELECT * FROM sleep_sessions ORDER BY startTime DESC LIMIT 1")
    fun observeLatest(): Flow<SleepSessionEntity?>

    @Query("SELECT * FROM sleep_sessions ORDER BY startTime DESC LIMIT 1")
    suspend fun getLatest(): SleepSessionEntity?

    @Upsert
    suspend fun upsertAll(sessions: List<SleepSessionEntity>)

    @Query("SELECT COUNT(*) FROM sleep_sessions WHERE startTime >= :fromMs")
    suspend fun countSince(fromMs: Long): Int

    @Query("SELECT * FROM sleep_sessions WHERE startTime >= :fromMs")
    suspend fun getSince(fromMs: Long): List<SleepSessionEntity>

    @Query(
        "SELECT * FROM sleep_sessions WHERE endTime >= :fromMs AND endTime < :toMs ORDER BY endTime ASC LIMIT 1",
    )
    suspend fun getSessionEndingInRange(
        fromMs: Long,
        toMs: Long,
    ): SleepSessionEntity?

    @Query(
        "SELECT * FROM sleep_sessions WHERE endTime >= :fromMs AND endTime < :toMs ORDER BY endTime ASC LIMIT 1",
    )
    fun observeFirstSessionEndingInRange(
        fromMs: Long,
        toMs: Long,
    ): Flow<SleepSessionEntity?>
}
