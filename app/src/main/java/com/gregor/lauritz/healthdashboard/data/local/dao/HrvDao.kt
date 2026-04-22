package com.gregor.lauritz.healthdashboard.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.gregor.lauritz.healthdashboard.data.local.entity.HrvRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HrvDao {
    @Query("SELECT * FROM hrv_records WHERE timestampMs >= :fromMs ORDER BY timestampMs ASC")
    fun observeSince(fromMs: Long): Flow<List<HrvRecordEntity>>

    @Query(
        "SELECT * FROM hrv_records WHERE recordType = 'SLEEP' AND timestampMs >= :fromMs " +
            "ORDER BY timestampMs ASC",
    )
    fun observeSleepHrvSince(fromMs: Long): Flow<List<HrvRecordEntity>>

    @Query(
        "SELECT rmssdMs FROM hrv_records WHERE recordType = 'SLEEP' AND timestampMs >= :fromMs " +
            "ORDER BY timestampMs ASC",
    )
    suspend fun getSleepRmssdValues(fromMs: Long): List<Float>

    @Query(
        "SELECT rmssdMs FROM hrv_records WHERE recordType = 'SLEEP' AND sessionId = :sessionId",
    )
    suspend fun getSleepRmssdForSession(sessionId: String): List<Float>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(records: List<HrvRecordEntity>)
}
