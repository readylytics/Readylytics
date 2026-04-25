package com.gregor.lauritz.healthdashboard.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.gregor.lauritz.healthdashboard.data.local.entity.DailySleepAverageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DailySleepAverageDao {
    @Upsert
    suspend fun upsert(entity: DailySleepAverageEntity)

    @Query("SELECT * FROM daily_sleep_averages WHERE dateMidnightMs >= :startMs AND dateMidnightMs <= :endMs ORDER BY dateMidnightMs ASC")
    fun observeRange(startMs: Long, endMs: Long): Flow<List<DailySleepAverageEntity>>

    @Query("SELECT * FROM daily_sleep_averages ORDER BY dateMidnightMs DESC LIMIT 1")
    suspend fun getLatest(): DailySleepAverageEntity?

    @Query("DELETE FROM daily_sleep_averages")
    suspend fun deleteAll(): Int
}
