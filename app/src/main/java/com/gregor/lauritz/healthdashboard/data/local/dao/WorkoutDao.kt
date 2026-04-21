package com.gregor.lauritz.healthdashboard.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.gregor.lauritz.healthdashboard.data.local.entity.WorkoutRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutDao {
    @Query("SELECT * FROM workout_records WHERE startTime >= :fromMs ORDER BY startTime DESC")
    fun observeSince(fromMs: Long): Flow<List<WorkoutRecordEntity>>

    @Query(
        "SELECT AVG(trimp) FROM workout_records WHERE startTime >= :fromMs AND startTime < :toMs",
    )
    suspend fun getAverageTrimp(
        fromMs: Long,
        toMs: Long,
    ): Float?

    @Query(
        "SELECT SUM(trimp) FROM workout_records WHERE startTime >= :fromMs AND startTime < :toMs",
    )
    suspend fun getTotalTrimp(
        fromMs: Long,
        toMs: Long,
    ): Float?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(records: List<WorkoutRecordEntity>)
}
