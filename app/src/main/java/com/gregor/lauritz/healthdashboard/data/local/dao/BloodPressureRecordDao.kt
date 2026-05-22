package com.gregor.lauritz.healthdashboard.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.gregor.lauritz.healthdashboard.data.local.entity.BloodPressureRecordEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

@Dao
interface BloodPressureRecordDao {
    @Query("SELECT * FROM blood_pressure_records WHERE timestampMs >= :fromMs ORDER BY timestampMs ASC")
    fun _observeSince(fromMs: Long): Flow<List<BloodPressureRecordEntity>>

    fun observeSince(fromMs: Long): Flow<List<BloodPressureRecordEntity>> = _observeSince(fromMs).distinctUntilChanged()

    @Query("SELECT * FROM blood_pressure_records WHERE timestampMs >= :fromMs ORDER BY timestampMs ASC")
    suspend fun getSince(fromMs: Long): List<BloodPressureRecordEntity>

    @Query(
        "SELECT * FROM blood_pressure_records WHERE timestampMs >= :startMs AND timestampMs <= :endMs " +
            "ORDER BY timestampMs ASC",
    )
    suspend fun getByTimeRange(
        startMs: Long,
        endMs: Long,
    ): List<BloodPressureRecordEntity>

    @Query(
        "SELECT * FROM blood_pressure_records " +
            "WHERE timestampMs >= :dayStartMs AND timestampMs < :dayEndMs " +
            "ORDER BY timestampMs DESC " +
            "LIMIT 1",
    )
    suspend fun getLatestByDate(
        dayStartMs: Long,
        dayEndMs: Long,
    ): BloodPressureRecordEntity?

    @Query("SELECT * FROM blood_pressure_records ORDER BY timestampMs DESC LIMIT 1")
    suspend fun getLatest(): BloodPressureRecordEntity?

    @Query("SELECT * FROM blood_pressure_records WHERE timestampMs <= :endMs ORDER BY timestampMs DESC LIMIT 1")
    suspend fun getLatestUpTo(endMs: Long): BloodPressureRecordEntity?

    @Upsert
    suspend fun upsertAll(records: List<BloodPressureRecordEntity>)

    @Query("DELETE FROM blood_pressure_records WHERE timestampMs < :beforeMs")
    suspend fun deleteBeforeTimestamp(beforeMs: Long): Int

    @Query("SELECT COUNT(*) FROM blood_pressure_records")
    suspend fun count(): Int

    @Query("DELETE FROM blood_pressure_records")
    suspend fun deleteAll(): Int
}
