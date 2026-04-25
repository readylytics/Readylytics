package com.gregor.lauritz.healthdashboard.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.gregor.lauritz.healthdashboard.data.local.entity.DailyRestingHeartRateAverageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyRestingHeartRateAverageDao {
    @Upsert
    suspend fun upsert(entity: DailyRestingHeartRateAverageEntity)

    @Query("SELECT * FROM daily_rhr_averages WHERE dateMidnightMs >= :startMs AND dateMidnightMs <= :endMs ORDER BY dateMidnightMs ASC")
    fun observeRange(startMs: Long, endMs: Long): Flow<List<DailyRestingHeartRateAverageEntity>>

    @Query("SELECT * FROM daily_rhr_averages ORDER BY dateMidnightMs DESC LIMIT 1")
    suspend fun getLatest(): DailyRestingHeartRateAverageEntity?

    @Query("DELETE FROM daily_rhr_averages")
    suspend fun deleteAll(): Int
}
