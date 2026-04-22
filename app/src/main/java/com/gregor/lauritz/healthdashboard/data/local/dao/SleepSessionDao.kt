package com.gregor.lauritz.healthdashboard.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sessions: List<SleepSessionEntity>)

    @Query("SELECT COUNT(*) FROM sleep_sessions WHERE startTime >= :fromMs")
    suspend fun countSince(fromMs: Long): Int
}
