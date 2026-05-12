package com.gregor.lauritz.healthdashboard.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.MapColumn
import androidx.room.Query
import androidx.room.Upsert
import com.gregor.lauritz.healthdashboard.data.local.entity.HeartRateRecordEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

@Dao
interface HeartRateDao {
    @Query(
        "SELECT * FROM heart_rate_records WHERE recordType = 'SLEEP' AND timestampMs >= :fromMs " +
            "ORDER BY timestampMs ASC",
    )
    fun _observeSleepHrSince(fromMs: Long): Flow<List<HeartRateRecordEntity>>
    fun observeSleepHrSince(fromMs: Long): Flow<List<HeartRateRecordEntity>> = _observeSleepHrSince(fromMs).distinctUntilChanged()

    @Query(
        "SELECT CAST(ROUND(AVG(beatsPerMinute)) AS INTEGER) FROM heart_rate_records " +
            "WHERE recordType = 'SLEEP' AND sessionId = :sessionId",
    )
    suspend fun getAvgSleepHr(sessionId: String): Int?

    @Query(
        "SELECT sessionId, CAST(ROUND(AVG(beatsPerMinute)) AS INTEGER) AS avgHr FROM heart_rate_records " +
            "WHERE recordType = 'SLEEP' AND sessionId IN (:sessionIds) " +
            "GROUP BY sessionId",
    )
    suspend fun getAvgSleepHrForSessions(
        sessionIds: List<String>,
    ): Map<@MapColumn(columnName = "sessionId") String, @MapColumn(columnName = "avgHr") Int>

    @Query(
        "SELECT CAST(ROUND(AVG(beatsPerMinute)) AS INTEGER) FROM heart_rate_records " +
            "WHERE recordType = 'SLEEP' AND sessionId IS NOT NULL AND timestampMs >= :fromMs " +
            "GROUP BY sessionId",
    )
    suspend fun getAvgSleepHrPerSession(fromMs: Long): List<Int>

    @Query(
        "SELECT beatsPerMinute FROM heart_rate_records " +
            "WHERE sessionId = :sessionId AND recordType = 'SLEEP' " +
            "ORDER BY beatsPerMinute ASC",
    )
    suspend fun getSleepHrSamplesForSession(sessionId: String): List<Int>

    @Query(
        "SELECT COUNT(*) FROM heart_rate_records " +
            "WHERE sessionId = :sessionId AND recordType = 'SLEEP'",
    )
    suspend fun getSleepHrSampleCount(sessionId: String): Int

    @Query(
        "SELECT beatsPerMinute FROM heart_rate_records " +
            "WHERE sessionId = :sessionId AND recordType = 'SLEEP' " +
            "ORDER BY beatsPerMinute ASC LIMIT 1 OFFSET :offset",
    )
    suspend fun getSleepHrSampleAtOffset(sessionId: String, offset: Int): Int?

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

    @Upsert
    suspend fun upsertAll(records: List<HeartRateRecordEntity>)

    @Query("DELETE FROM heart_rate_records WHERE timestampMs < :beforeMs")
    suspend fun deleteBeforeTimestamp(beforeMs: Long): Int

    @Query("DELETE FROM heart_rate_records")
    suspend fun deleteAll(): Int
}
