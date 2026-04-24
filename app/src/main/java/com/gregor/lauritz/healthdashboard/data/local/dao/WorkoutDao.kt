package com.gregor.lauritz.healthdashboard.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.gregor.lauritz.healthdashboard.data.local.entity.WorkoutRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutDao {
    @Query("SELECT * FROM workout_records WHERE id = :id")
    suspend fun getById(id: String): WorkoutRecordEntity?

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

    @Query(
        "SELECT SUM(trimp) FROM workout_records " +
            "WHERE startTime >= :fromMs AND startTime < :toMs " +
            "GROUP BY (startTime + :tzOffsetMs) / 86400000 " +
            "ORDER BY (startTime + :tzOffsetMs) / 86400000 ASC",
    )
    suspend fun getDailyTrimp(
        fromMs: Long,
        toMs: Long,
        tzOffsetMs: Long
    ): List<Float>

    @Query("SELECT MIN(startTime) FROM workout_records")
    suspend fun getEarliestWorkoutTimestamp(): Long?

    @Upsert
    suspend fun upsertAll(records: List<WorkoutRecordEntity>)
}
