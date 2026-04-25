package com.gregor.lauritz.healthdashboard.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.gregor.lauritz.healthdashboard.data.local.entity.HrvRecordEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

@Dao
interface HrvDao {
    @Query("SELECT * FROM hrv_records WHERE timestampMs >= :fromMs ORDER BY timestampMs ASC")
    fun _observeSince(fromMs: Long): Flow<List<HrvRecordEntity>>
    fun observeSince(fromMs: Long): Flow<List<HrvRecordEntity>> = _observeSince(fromMs).distinctUntilChanged()

    @Query(
        "SELECT * FROM hrv_records WHERE recordType = 'SLEEP' AND timestampMs >= :fromMs " +
            "ORDER BY timestampMs ASC",
    )
    fun _observeSleepHrvSince(fromMs: Long): Flow<List<HrvRecordEntity>>
    fun observeSleepHrvSince(fromMs: Long): Flow<List<HrvRecordEntity>> = _observeSleepHrvSince(fromMs).distinctUntilChanged()

    @Query(
        "SELECT rmssdMs FROM hrv_records WHERE recordType = 'SLEEP' AND timestampMs >= :fromMs " +
            "ORDER BY timestampMs ASC",
    )
    suspend fun getSleepRmssdValues(fromMs: Long): List<Float>

    @Query(
        "SELECT rmssdMs FROM hrv_records WHERE recordType = 'SLEEP' AND sessionId = :sessionId",
    )
    suspend fun getSleepRmssdForSession(sessionId: String): List<Float>

    @Upsert
    suspend fun upsertAll(records: List<HrvRecordEntity>)

    @Query("DELETE FROM hrv_records WHERE timestampMs < :beforeMs")
    suspend fun deleteBeforeTimestamp(beforeMs: Long): Int

    @Query("DELETE FROM hrv_records")
    suspend fun deleteAll(): Int
}
