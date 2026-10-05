package app.readylytics.health.core.databaseschema.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

@Dao
interface HeartRateObservationDao {
    @Query(
        "SELECT COUNT(*) FROM heart_rate_records " +
            "WHERE sessionId = :sessionId AND recordType = 'SLEEP' " +
            "AND beatsPerMinute BETWEEN 30 AND 230",
    )
    suspend fun getSleepHrSampleCount(sessionId: String): Int

    @Query(
        "SELECT beatsPerMinute FROM heart_rate_records " +
            "WHERE sessionId = :sessionId AND recordType = 'SLEEP' " +
            "AND beatsPerMinute BETWEEN 30 AND 230 " +
            "ORDER BY beatsPerMinute ASC, timestampMs ASC, sourceRecordRef ASC LIMIT 1 OFFSET :offset",
    )
    suspend fun getSleepHrSampleAtOffset(
        sessionId: String,
        offset: Int,
    ): Int?

    @Query(
        "SELECT * FROM heart_rate_records " +
            "WHERE sessionId = :sessionId AND recordType = 'SLEEP' " +
            "ORDER BY timestampMs ASC, sourceRecordRef ASC",
    )
    fun _observeSleepHrTimelineForSession(sessionId: String): Flow<List<HeartRateRecordEntity>>

    fun observeSleepHrTimelineForSession(sessionId: String): Flow<List<HeartRateRecordEntity>> =
        _observeSleepHrTimelineForSession(sessionId).distinctUntilChanged()

    @Query(
        "SELECT h.* FROM heart_rate_records h " +
            "LEFT JOIN minute_coverage c ON c.bucketStartMs = " +
            "((h.timestampMs / 60000) - (CASE WHEN h.timestampMs % 60000 < 0 THEN 1 ELSE 0 END)) * 60000 " +
            "WHERE h.sessionId = :sessionId AND h.recordType = 'SLEEP' " +
            "AND (c.bucketStartMs IS NULL OR c.tier = 'HOT' " +
            "OR (c.tier IN ('WARM', 'LEGACY_WARM') AND NOT EXISTS (" +
            "SELECT 1 FROM hr_minute_buckets b2 WHERE b2.bucketStartMs = c.bucketStartMs " +
            "AND b2.generation = c.visibleGeneration))) " +
            "ORDER BY h.timestampMs ASC, h.sourceRecordRef ASC",
    )
    fun _observeVisibleSleepHrTimelineForSession(sessionId: String): Flow<List<HeartRateRecordEntity>>

    fun observeVisibleSleepHrTimelineForSession(sessionId: String): Flow<List<HeartRateRecordEntity>> =
        _observeVisibleSleepHrTimelineForSession(sessionId).distinctUntilChanged()

    @Query(
        "SELECT MIN(beatsPerMinute) FROM heart_rate_records " +
            "WHERE timestampMs >= :startTimeMs AND timestampMs <= :endTimeMs " +
            "AND beatsPerMinute BETWEEN 30 AND 230",
    )
    suspend fun getMinHrInRange(
        startTimeMs: Long,
        endTimeMs: Long,
    ): Int?

    @Query(
        "SELECT timestampMs FROM heart_rate_records " +
            "WHERE recordType = 'SLEEP' AND sessionId = :sessionId " +
            "AND beatsPerMinute BETWEEN 30 AND 230 " +
            "ORDER BY beatsPerMinute ASC, timestampMs ASC, sourceRecordRef ASC LIMIT 1",
    )
    suspend fun getMinHrTimestamp(sessionId: String): Long?

    @Query(
        "SELECT * FROM heart_rate_records WHERE timestampMs >= :startMs AND timestampMs < :endMs " +
            "ORDER BY timestampMs ASC, sourceRecordRef ASC",
    )
    fun _observeByTimeRange(
        startMs: Long,
        endMs: Long,
    ): Flow<List<HeartRateRecordEntity>>

    fun observeByTimeRange(
        startMs: Long,
        endMs: Long,
    ): Flow<List<HeartRateRecordEntity>> = _observeByTimeRange(startMs, endMs).distinctUntilChanged()
}
