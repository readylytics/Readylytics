package com.gregor.lauritz.healthdashboard.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DailySummaryDao {
    @Query("SELECT * FROM daily_summaries ORDER BY dateMidnightMs DESC LIMIT 1")
    fun observeLatest(): Flow<DailySummaryEntity?>

    @Query(
        "SELECT * FROM daily_summaries WHERE dateMidnightMs >= :fromMs " +
            "ORDER BY dateMidnightMs DESC",
    )
    fun observeSince(fromMs: Long): Flow<List<DailySummaryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(summary: DailySummaryEntity)

    @Query("SELECT * FROM daily_summaries WHERE dateMidnightMs = :dateMidnightMs")
    suspend fun getByDate(dateMidnightMs: Long): DailySummaryEntity?
}
