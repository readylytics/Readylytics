package com.gregor.lauritz.healthdashboard.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.gregor.lauritz.healthdashboard.data.local.entity.OxygenSaturationRecordEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

@Dao
interface OxygenSaturationRecordDao {
    @Query("SELECT * FROM oxygen_saturation_records WHERE timestampMs >= :fromMs ORDER BY timestampMs ASC")
    fun _observeSince(fromMs: Long): Flow<List<OxygenSaturationRecordEntity>>

    fun observeSince(fromMs: Long): Flow<List<OxygenSaturationRecordEntity>> =
        _observeSince(fromMs).distinctUntilChanged()

    @Query(
        "SELECT * FROM oxygen_saturation_records WHERE timestampMs >= :startMs AND timestampMs <= :endMs " +
            "ORDER BY timestampMs ASC",
    )
    fun _observeByTimeRange(
        startMs: Long,
        endMs: Long,
    ): Flow<List<OxygenSaturationRecordEntity>>

    fun observeByTimeRange(
        startMs: Long,
        endMs: Long,
    ): Flow<List<OxygenSaturationRecordEntity>> = _observeByTimeRange(startMs, endMs).distinctUntilChanged()

    @Query("SELECT * FROM oxygen_saturation_records WHERE timestampMs >= :fromMs ORDER BY timestampMs ASC")
    suspend fun getSince(fromMs: Long): List<OxygenSaturationRecordEntity>

    @Query(
        "SELECT * FROM oxygen_saturation_records WHERE timestampMs >= :startMs AND timestampMs <= :endMs " +
            "ORDER BY timestampMs ASC",
    )
    suspend fun getByTimeRange(
        startMs: Long,
        endMs: Long,
    ): List<OxygenSaturationRecordEntity>

    @Query(
        "SELECT * FROM oxygen_saturation_records " +
            "WHERE timestampMs >= :dayStartMs AND timestampMs < :dayEndMs " +
            "ORDER BY timestampMs DESC " +
            "LIMIT 1",
    )
    suspend fun getLatestByDate(
        dayStartMs: Long,
        dayEndMs: Long,
    ): OxygenSaturationRecordEntity?

    @Query("SELECT * FROM oxygen_saturation_records ORDER BY timestampMs DESC LIMIT 1")
    suspend fun getLatest(): OxygenSaturationRecordEntity?

    @Query("SELECT * FROM oxygen_saturation_records WHERE timestampMs <= :endMs ORDER BY timestampMs DESC LIMIT 1")
    suspend fun getLatestUpTo(endMs: Long): OxygenSaturationRecordEntity?

    @Upsert
    suspend fun upsertAll(records: List<OxygenSaturationRecordEntity>)

    @Query("DELETE FROM oxygen_saturation_records WHERE timestampMs < :beforeMs")
    suspend fun deleteBeforeTimestamp(beforeMs: Long): Int

    @Query("SELECT COUNT(*) FROM oxygen_saturation_records")
    suspend fun count(): Int

    @Query("DELETE FROM oxygen_saturation_records")
    suspend fun deleteAll(): Int
}
