package com.gregor.lauritz.healthdashboard.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.gregor.lauritz.healthdashboard.data.local.entity.BodyFatRecordEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

@Dao
interface BodyFatRecordDao {
    @Query("SELECT * FROM body_fat_records WHERE timestampMs >= :fromMs ORDER BY timestampMs ASC")
    fun _observeSince(fromMs: Long): Flow<List<BodyFatRecordEntity>>

    fun observeSince(fromMs: Long): Flow<List<BodyFatRecordEntity>> =
        _observeSince(fromMs).distinctUntilChanged()

    @Query("SELECT * FROM body_fat_records WHERE timestampMs >= :fromMs ORDER BY timestampMs ASC")
    suspend fun getSince(fromMs: Long): List<BodyFatRecordEntity>

    @Query(
        "SELECT * FROM body_fat_records WHERE timestampMs >= :startMs AND timestampMs <= :endMs " +
            "ORDER BY timestampMs ASC",
    )
    suspend fun getByTimeRange(
        startMs: Long,
        endMs: Long,
    ): List<BodyFatRecordEntity>

    @Query(
        "SELECT * FROM body_fat_records " +
            "WHERE timestampMs >= :dayStartMs AND timestampMs < :dayEndMs " +
            "ORDER BY timestampMs DESC " +
            "LIMIT 1",
    )
    suspend fun getLatestByDate(
        dayStartMs: Long,
        dayEndMs: Long,
    ): BodyFatRecordEntity?

    @Query("SELECT * FROM body_fat_records ORDER BY timestampMs DESC LIMIT 1")
    suspend fun getLatest(): BodyFatRecordEntity?

    @Upsert
    suspend fun upsertAll(records: List<BodyFatRecordEntity>)

    @Query("DELETE FROM body_fat_records WHERE timestampMs < :beforeMs")
    suspend fun deleteBeforeTimestamp(beforeMs: Long): Int

    @Query("SELECT COUNT(*) FROM body_fat_records")
    suspend fun count(): Int

    @Query("DELETE FROM body_fat_records")
    suspend fun deleteAll(): Int
}
