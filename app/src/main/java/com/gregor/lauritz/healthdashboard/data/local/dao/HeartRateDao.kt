package com.gregor.lauritz.healthdashboard.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.gregor.lauritz.healthdashboard.data.local.entity.HeartRateRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HeartRateDao {
    @Query(
        "SELECT * FROM heart_rate_records WHERE recordType = 'SLEEP' AND timestampMs >= :fromMs " +
            "ORDER BY timestampMs ASC",
    )
    fun observeSleepHrSince(fromMs: Long): Flow<List<HeartRateRecordEntity>>

    @Query(
        "SELECT CAST(ROUND(AVG(beatsPerMinute)) AS INTEGER) FROM heart_rate_records " +
            "WHERE recordType = 'SLEEP' AND sessionId = :sessionId",
    )
    suspend fun getAvgSleepHr(sessionId: String): Int?

    @Query(
        "SELECT CAST(ROUND(AVG(beatsPerMinute)) AS INTEGER) FROM heart_rate_records " +
            "WHERE recordType = 'SLEEP' AND sessionId IS NOT NULL AND timestampMs >= :fromMs " +
            "GROUP BY sessionId",
    )
    suspend fun getAvgSleepHrPerSession(fromMs: Long): List<Int>

    @Query(
        "SELECT MIN(beatsPerMinute) FROM heart_rate_records " +
            "WHERE timestampMs >= :startTimeMs AND timestampMs <= :endTimeMs",
    )
    suspend fun getMinHrInRange(
        startTimeMs: Long,
        endTimeMs: Long,
    ): Int?

    @Query(
        "SELECT timestampMs FROM heart_rate_records " +
            "WHERE recordType = 'SLEEP' AND sessionId = :sessionId " +
            "ORDER BY beatsPerMinute ASC, timestampMs ASC LIMIT 1",
    )
    suspend fun getMinHrTimestamp(sessionId: String): Long?

    @Query(
        "SELECT * FROM heart_rate_records WHERE timestampMs >= :startMs AND timestampMs <= :endMs " +
            "ORDER BY timestampMs ASC",
    )
    suspend fun getByTimeRange(
        startMs: Long,
        endMs: Long,
    ): List<HeartRateRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(records: List<HeartRateRecordEntity>)
}
