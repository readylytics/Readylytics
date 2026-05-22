package com.gregor.lauritz.healthdashboard.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.gregor.lauritz.healthdashboard.data.local.entity.WeightRecordEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

@Dao
interface WeightRecordDao {
    @Query("SELECT * FROM weight_records WHERE timestampMs >= :fromMs ORDER BY timestampMs ASC")
    fun _observeSince(fromMs: Long): Flow<List<WeightRecordEntity>>

    fun observeSince(fromMs: Long): Flow<List<WeightRecordEntity>> = _observeSince(fromMs).distinctUntilChanged()

    @Query(
        "SELECT * FROM weight_records WHERE timestampMs >= :startMs AND timestampMs <= :endMs " +
            "ORDER BY timestampMs ASC",
    )
    fun _observeByTimeRange(
        startMs: Long,
        endMs: Long,
    ): Flow<List<WeightRecordEntity>>

    fun observeByTimeRange(
        startMs: Long,
        endMs: Long,
    ): Flow<List<WeightRecordEntity>> = _observeByTimeRange(startMs, endMs).distinctUntilChanged()

    @Query("SELECT * FROM weight_records WHERE timestampMs >= :fromMs ORDER BY timestampMs ASC")
    suspend fun getSince(fromMs: Long): List<WeightRecordEntity>

    @Query(
        "SELECT * FROM weight_records WHERE timestampMs >= :startMs AND timestampMs <= :endMs " +
            "ORDER BY timestampMs ASC",
    )
    suspend fun getByTimeRange(
        startMs: Long,
        endMs: Long,
    ): List<WeightRecordEntity>

    @Query(
        "SELECT * FROM weight_records " +
            "WHERE timestampMs >= :dayStartMs AND timestampMs < :dayEndMs " +
            "ORDER BY timestampMs DESC " +
            "LIMIT 1",
    )
    suspend fun getLatestByDate(
        dayStartMs: Long,
        dayEndMs: Long,
    ): WeightRecordEntity?

    @Query("SELECT * FROM weight_records ORDER BY timestampMs DESC LIMIT 1")
    suspend fun getLatest(): WeightRecordEntity?

    @Query("SELECT * FROM weight_records WHERE timestampMs <= :endMs ORDER BY timestampMs DESC LIMIT 1")
    suspend fun getLatestUpTo(endMs: Long): WeightRecordEntity?

    @Upsert
    suspend fun upsertAll(records: List<WeightRecordEntity>)

    @Query("DELETE FROM weight_records WHERE timestampMs < :beforeMs")
    suspend fun deleteBeforeTimestamp(beforeMs: Long): Int

    @Query("SELECT COUNT(*) FROM weight_records")
    suspend fun count(): Int

    @Query("DELETE FROM weight_records")
    suspend fun deleteAll(): Int
}
