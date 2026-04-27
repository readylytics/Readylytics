package com.gregor.lauritz.healthdashboard.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

@Dao
interface DailySummaryDao {
    @Query("SELECT * FROM daily_summaries ORDER BY dateMidnightMs DESC LIMIT 1")
    fun _observeLatest(): Flow<DailySummaryEntity?>
    fun observeLatest(): Flow<DailySummaryEntity?> = _observeLatest().distinctUntilChanged()

    @Query(
        "SELECT * FROM daily_summaries WHERE dateMidnightMs >= :fromMs " +
            "ORDER BY dateMidnightMs DESC",
    )
    fun _observeSince(fromMs: Long): Flow<List<DailySummaryEntity>>
    fun observeSince(fromMs: Long): Flow<List<DailySummaryEntity>> = _observeSince(fromMs).distinctUntilChanged()

    @Upsert
    suspend fun upsert(summary: DailySummaryEntity)

    @Upsert
    suspend fun upsertAll(summaries: List<DailySummaryEntity>)

    @Query("SELECT * FROM daily_summaries WHERE dateMidnightMs = :dateMidnightMs")
    suspend fun getByDate(dateMidnightMs: Long): DailySummaryEntity?

    @Query("SELECT * FROM daily_summaries WHERE dateMidnightMs >= :fromMs ORDER BY dateMidnightMs ASC")
    suspend fun getSince(fromMs: Long): List<DailySummaryEntity>

    @Query("DELETE FROM daily_summaries WHERE dateMidnightMs < :beforeMs")
    suspend fun deleteBeforeTimestamp(beforeMs: Long): Int

    @Query("DELETE FROM daily_summaries")
    suspend fun deleteAll(): Int
}
