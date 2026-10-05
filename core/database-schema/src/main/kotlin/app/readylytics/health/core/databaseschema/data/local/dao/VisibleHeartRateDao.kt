package app.readylytics.health.core.databaseschema.data.local.dao


import androidx.room.Dao
import androidx.room.MapColumn
import androidx.room.Transaction
import androidx.room.RawQuery
import androidx.room.RoomRawQuery
import androidx.room.Query
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.model.domain.model.HrMinuteBucketRow
import app.readylytics.health.core.model.domain.model.HrRangeAggregate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

interface VisibleHeartRateDao {
    /** Tier-authoritative equivalent of [getByTimeRange] (inclusive `endMs`, matching it). */
    @Query(
        "SELECT h.* FROM heart_rate_records h " +
            "LEFT JOIN minute_coverage c ON c.bucketStartMs = " +
            "((h.timestampMs / 60000) - (CASE WHEN h.timestampMs % 60000 < 0 THEN 1 ELSE 0 END)) * 60000 " +
            "WHERE h.timestampMs >= :startMs AND h.timestampMs <= :endMs " +
            "AND (c.bucketStartMs IS NULL OR c.tier = 'HOT' " +
            "OR (c.tier IN ('WARM', 'LEGACY_WARM') AND NOT EXISTS (" +
            "SELECT 1 FROM hr_minute_buckets b2 WHERE b2.bucketStartMs = c.bucketStartMs " +
            "AND b2.generation = c.visibleGeneration))) " +
            "ORDER BY h.timestampMs ASC, h.sourceRecordRef ASC",
    )
    suspend fun getVisibleByTimeRange(
        startMs: Long,
        endMs: Long,
    ): List<HeartRateRecordEntity>

    /** Tier-authoritative equivalent of [getByTypeAndTimeRange] (inclusive `endMs`, matching it). */
    @Query(
        "SELECT h.* FROM heart_rate_records h " +
            "LEFT JOIN minute_coverage c ON c.bucketStartMs = " +
            "((h.timestampMs / 60000) - (CASE WHEN h.timestampMs % 60000 < 0 THEN 1 ELSE 0 END)) * 60000 " +
            "WHERE h.recordType = :recordType " +
            "AND h.timestampMs >= :startMs AND h.timestampMs <= :endMs " +
            "AND (c.bucketStartMs IS NULL OR c.tier = 'HOT' " +
            "OR (c.tier IN ('WARM', 'LEGACY_WARM') AND NOT EXISTS (" +
            "SELECT 1 FROM hr_minute_buckets b2 WHERE b2.bucketStartMs = c.bucketStartMs " +
            "AND b2.generation = c.visibleGeneration))) " +
            "ORDER BY h.timestampMs ASC, h.sourceRecordRef ASC",
    )
    suspend fun getVisibleByTypeAndTimeRange(
        recordType: String,
        startMs: Long,
        endMs: Long,
    ): List<HeartRateRecordEntity>

    /** Tier-authoritative equivalent of [_observeByTimeRange] (exclusive `endMs`, matching it). */
    @Query(
        "SELECT h.* FROM heart_rate_records h " +
            "LEFT JOIN minute_coverage c ON c.bucketStartMs = " +
            "((h.timestampMs / 60000) - (CASE WHEN h.timestampMs % 60000 < 0 THEN 1 ELSE 0 END)) * 60000 " +
            "WHERE h.timestampMs >= :startMs AND h.timestampMs < :endMs " +
            "AND (c.bucketStartMs IS NULL OR c.tier = 'HOT' " +
            "OR (c.tier IN ('WARM', 'LEGACY_WARM') AND NOT EXISTS (" +
            "SELECT 1 FROM hr_minute_buckets b2 WHERE b2.bucketStartMs = c.bucketStartMs " +
            "AND b2.generation = c.visibleGeneration))) " +
            "ORDER BY h.timestampMs ASC, h.sourceRecordRef ASC",
    )
    fun _observeVisibleByTimeRange(
        startMs: Long,
        endMs: Long,
    ): Flow<List<HeartRateRecordEntity>>

    fun observeVisibleByTimeRange(
        startMs: Long,
        endMs: Long,
    ): Flow<List<HeartRateRecordEntity>> = _observeVisibleByTimeRange(startMs, endMs).distinctUntilChanged()

    /** Tier-authoritative equivalent of [getMinuteBuckets] (exclusive `dayEndMs`, matching it). */
    @Query(
        "SELECT (h.timestampMs - :dayStartMs) / 60000 AS bucketIndex, " +
            "AVG(h.beatsPerMinute) AS avgBpm, COUNT(*) AS sampleCount " +
            "FROM heart_rate_records h " +
            "LEFT JOIN minute_coverage c ON c.bucketStartMs = " +
            "((h.timestampMs / 60000) - (CASE WHEN h.timestampMs % 60000 < 0 THEN 1 ELSE 0 END)) * 60000 " +
            "WHERE h.timestampMs >= :dayStartMs AND h.timestampMs < :dayEndMs " +
            "AND h.beatsPerMinute BETWEEN 30 AND 230 " +
            "AND (c.bucketStartMs IS NULL OR c.tier = 'HOT' " +
            "OR (c.tier IN ('WARM', 'LEGACY_WARM') AND NOT EXISTS (" +
            "SELECT 1 FROM hr_minute_buckets b2 WHERE b2.bucketStartMs = c.bucketStartMs " +
            "AND b2.generation = c.visibleGeneration))) " +
            "GROUP BY bucketIndex " +
            "ORDER BY bucketIndex ASC",
    )
    suspend fun getVisibleMinuteBuckets(
        dayStartMs: Long,
        dayEndMs: Long,
    ): List<HrMinuteBucketRow>

    /** Tier-authoritative equivalent of [getSleepHrProjectionForSessions]. */
    @Query(
        "SELECT h.sessionId AS sessionId, h.beatsPerMinute AS beatsPerMinute " +
            "FROM heart_rate_records h " +
            "LEFT JOIN minute_coverage c ON c.bucketStartMs = " +
            "((h.timestampMs / 60000) - (CASE WHEN h.timestampMs % 60000 < 0 THEN 1 ELSE 0 END)) * 60000 " +
            "WHERE h.sessionId IN (:sessionIds) AND h.recordType = 'SLEEP' " +
            "AND h.beatsPerMinute BETWEEN 30 AND 230 " +
            "AND (c.bucketStartMs IS NULL OR c.tier = 'HOT' " +
            "OR (c.tier IN ('WARM', 'LEGACY_WARM') AND NOT EXISTS (" +
            "SELECT 1 FROM hr_minute_buckets b2 WHERE b2.bucketStartMs = c.bucketStartMs " +
            "AND b2.generation = c.visibleGeneration))) " +
            "ORDER BY h.sessionId ASC, h.beatsPerMinute ASC, h.timestampMs ASC, h.sourceRecordRef ASC",
    )
    suspend fun getVisibleSleepHrProjectionForSessions(sessionIds: List<String>): List<SleepHrSample>

    /** Tier-authoritative grouped raw projection for sleep sessions. */
    @Query(
        "SELECT h.sessionId AS sessionId, " +
            "SUM(h.beatsPerMinute) AS sumBpm, " +
            "COUNT(*) AS sampleCount " +
            "FROM heart_rate_records h " +
            "LEFT JOIN minute_coverage c ON c.bucketStartMs = " +
            "((h.timestampMs / 60000) - (CASE WHEN h.timestampMs % 60000 < 0 THEN 1 ELSE 0 END)) * 60000 " +
            "WHERE h.sessionId IN (:sessionIds) AND h.recordType = 'SLEEP' " +
            "AND h.beatsPerMinute BETWEEN 30 AND 230 " +
            "AND (c.bucketStartMs IS NULL OR c.tier = 'HOT' " +
            "OR (c.tier IN ('WARM', 'LEGACY_WARM') AND NOT EXISTS (" +
            "SELECT 1 FROM hr_minute_buckets b2 WHERE b2.bucketStartMs = c.bucketStartMs " +
            "AND b2.generation = c.visibleGeneration))) " +
            "GROUP BY h.sessionId",
    )
    suspend fun getVisibleSleepHrSummaryForSessions(sessionIds: List<String>): List<SleepHrRawSummary>

    /** Tier-authoritative equivalent of [getSleepHrSamplesForSession]. */
    @Query(
        "SELECT h.beatsPerMinute FROM heart_rate_records h " +
            "LEFT JOIN minute_coverage c ON c.bucketStartMs = " +
            "((h.timestampMs / 60000) - (CASE WHEN h.timestampMs % 60000 < 0 THEN 1 ELSE 0 END)) * 60000 " +
            "WHERE h.sessionId = :sessionId AND h.recordType = 'SLEEP' " +
            "AND h.beatsPerMinute BETWEEN 30 AND 230 " +
            "AND (c.bucketStartMs IS NULL OR c.tier = 'HOT' " +
            "OR (c.tier IN ('WARM', 'LEGACY_WARM') AND NOT EXISTS (" +
            "SELECT 1 FROM hr_minute_buckets b2 WHERE b2.bucketStartMs = c.bucketStartMs " +
            "AND b2.generation = c.visibleGeneration))) " +
            "ORDER BY h.beatsPerMinute ASC, h.timestampMs ASC, h.sourceRecordRef ASC",
    )
    suspend fun getVisibleSleepHrSamplesForSession(sessionId: String): List<Int>

    /** Tier-authoritative equivalent of [getMinHrInRange] (inclusive `endTimeMs`, matching it). */
    @Query(
        "SELECT MIN(h.beatsPerMinute) FROM heart_rate_records h " +
            "LEFT JOIN minute_coverage c ON c.bucketStartMs = " +
            "((h.timestampMs / 60000) - (CASE WHEN h.timestampMs % 60000 < 0 THEN 1 ELSE 0 END)) * 60000 " +
            "WHERE h.timestampMs >= :startTimeMs AND h.timestampMs <= :endTimeMs " +
            "AND h.beatsPerMinute BETWEEN 30 AND 230 " +
            "AND (c.bucketStartMs IS NULL OR c.tier = 'HOT' " +
            "OR (c.tier IN ('WARM', 'LEGACY_WARM') AND NOT EXISTS (" +
            "SELECT 1 FROM hr_minute_buckets b2 WHERE b2.bucketStartMs = c.bucketStartMs " +
            "AND b2.generation = c.visibleGeneration)))",
    )
    suspend fun getVisibleMinHrInRange(
        startTimeMs: Long,
        endTimeMs: Long,
    ): Int?

    // Conflict-targeted UPSERT on the natural unique key (sourceRecordRef, timestampMs): updates
    // mutable columns (recordType/sessionId/deviceName) in place and preserves rowId — unlike
    // SQLite REPLACE, which deletes+reinserts and rotates rowId on every re-upsert. The WHERE
    // predicate makes an identical re-ingest a near-no-op (SQLite changes() = 0).
}
