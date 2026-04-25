package com.gregor.lauritz.healthdashboard.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.gregor.lauritz.healthdashboard.data.local.entity.DailyHrvAverageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyHrvAverageDao {
    @Upsert
    suspend fun upsert(entity: DailyHrvAverageEntity)

    @Query("SELECT * FROM daily_hrv_averages WHERE dateMidnightMs >= :startMs AND dateMidnightMs <= :endMs ORDER BY dateMidnightMs ASC")
    fun observeRange(startMs: Long, endMs: Long): Flow<List<DailyHrvAverageEntity>>

    @Query("SELECT * FROM daily_hrv_averages ORDER BY dateMidnightMs DESC LIMIT 1")
    suspend fun getLatest(): DailyHrvAverageEntity?

    @Query("DELETE FROM daily_hrv_averages")
    suspend fun deleteAll(): Int
}
