package app.readylytics.health.data.local.dao

import androidx.room.Dao
import androidx.room.MapColumn
import androidx.room.Query
import androidx.room.Upsert
import app.readylytics.health.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.domain.model.HrMinuteBucketRow
import app.readylytics.health.domain.model.HrRangeAggregate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

@Dao
interface HeartRateDao {
    @Query("SELECT * FROM heart_rate_records WHERE timestampMs >= :fromMs ORDER BY timestampMs ASC, sourceRecordId ASC")
    suspend fun getSince(fromMs: Long): List<HeartRateRecordEntity>

    @Query(
        "SELECT * FROM heart_rate_records WHERE timestampMs >= :fromMs ORDER BY timestampMs ASC, sourceRecordId ASC LIMIT :limit OFFSET :offset",
    )
    suspend fun getPaged(
        fromMs: Long,
        limit: Int,
        offset: Int,
    ): List<HeartRateRecordEntity>

    @Query(
        "SELECT * FROM heart_rate_records " +
            "WHERE timestampMs >= :startMs AND timestampMs <= :endMs " +
            "AND (timestampMs > :lastTimestampMs OR (timestampMs = :lastTimestampMs AND sourceRecordId > :lastId)) " +
            "ORDER BY timestampMs ASC, sourceRecordId ASC LIMIT :limit",
    )
    suspend fun getKeysetPage(
        startMs: Long,
        endMs: Long,
        lastTimestampMs: Long,
        lastId: String,
        limit: Int,
    ): List<HeartRateRecordEntity>

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
    ): Map<
        @MapColumn(columnName = "sessionId")
        String,
        @MapColumn(columnName = "avgHr")
        Int,
    >

    @Query(
        "SELECT CAST(ROUND(AVG(beatsPerMinute)) AS INTEGER) FROM heart_rate_records " +
            "WHERE recordType = 'SLEEP' AND sessionId IS NOT NULL AND timestampMs >= :fromMs " +
            "GROUP BY sessionId",
    )
    suspend fun getAvgSleepHrPerSession(fromMs: Long): List<Int>

    @Query(
        "SELECT beatsPerMinute FROM heart_rate_records " +
            "WHERE sessionId = :sessionId AND recordType = 'SLEEP' " +
            "ORDER BY beatsPerMinute ASC, timestampMs ASC, sourceRecordId ASC",
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
            "ORDER BY beatsPerMinute ASC, timestampMs ASC, sourceRecordId ASC LIMIT 1 OFFSET :offset",
    )
    suspend fun getSleepHrSampleAtOffset(
        sessionId: String,
        offset: Int,
    ): Int?

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
            "ORDER BY beatsPerMinute ASC, timestampMs ASC, sourceRecordId ASC LIMIT 1",
    )
    suspend fun getMinHrTimestamp(sessionId: String): Long?

    @Query(
        "SELECT * FROM heart_rate_records WHERE timestampMs >= :startMs AND timestampMs <= :endMs " +
            "ORDER BY timestampMs ASC, sourceRecordId ASC",
    )
    suspend fun getByTimeRange(
        startMs: Long,
        endMs: Long,
    ): List<HeartRateRecordEntity>

    @Query(
        "SELECT * FROM heart_rate_records WHERE timestampMs >= :startMs AND timestampMs < :endMs " +
            "ORDER BY timestampMs ASC, sourceRecordId ASC",
    )
    fun _observeByTimeRange(
        startMs: Long,
        endMs: Long,
    ): Flow<List<HeartRateRecordEntity>>

    fun observeByTimeRange(
        startMs: Long,
        endMs: Long,
    ): Flow<List<HeartRateRecordEntity>> = _observeByTimeRange(startMs, endMs).distinctUntilChanged()

    @Upsert
    suspend fun upsertAll(records: List<HeartRateRecordEntity>)

    @Query("DELETE FROM heart_rate_records WHERE timestampMs < :beforeMs")
    suspend fun deleteBeforeTimestamp(beforeMs: Long): Int

    @Query("DELETE FROM heart_rate_records WHERE sourceRecordId = :id")
    suspend fun deleteById(id: String): Int

    @Query("SELECT * FROM heart_rate_records WHERE sourceRecordId = :id")
    suspend fun getById(id: String): HeartRateRecordEntity?

    @Query(
        "SELECT * FROM heart_rate_records " +
            "WHERE sourceRecordId = :sourceRecordId " +
            "OR (sourceRecordId >= :sourceRecordId || '_' AND sourceRecordId < :sourceRecordId || '`') " +
            "ORDER BY timestampMs ASC, sourceRecordId ASC",
    )
    suspend fun getBySourceRecordId(sourceRecordId: String): List<HeartRateRecordEntity>

    @Query(
        "DELETE FROM heart_rate_records " +
            "WHERE sourceRecordId = :sourceRecordId " +
            "OR (sourceRecordId >= :sourceRecordId || '_' AND sourceRecordId < :sourceRecordId || '`')",
    )
    suspend fun deleteBySourceRecordId(sourceRecordId: String): Int

    @Query("SELECT COUNT(*) FROM heart_rate_records")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM heart_rate_records WHERE timestampMs >= :startMs AND timestampMs <= :endMs")
    suspend fun countInRange(startMs: Long, endMs: Long): Int

    @Query("DELETE FROM heart_rate_records")
    suspend fun deleteAll(): Int

    @Query("SELECT DISTINCT deviceName FROM heart_rate_records WHERE deviceName IS NOT NULL AND deviceName != ''")
    suspend fun getDistinctDeviceNames(): List<String>

    @Query(
        "DELETE FROM heart_rate_records WHERE timestampMs >= :fromMs AND timestampMs < :toMs AND (deviceName != :deviceName OR deviceName IS NULL)",
    )
    suspend fun deleteRecordsNotMatchingDevice(
        fromMs: Long,
        toMs: Long,
        deviceName: String,
    ): Int

    /**
     * Batch fetch all sleep HR samples for multiple sessions in a single query.
     * Used to fix N+1 query pattern in baseline computation.
     *
     * Returns all records with their sessionId so they can be grouped in memory.
     * More efficient than per-session queries for computing statistics.
     */
    @Query(
        "SELECT rowId, sourceRecordId, sessionId, recordType, beatsPerMinute, timestampMs, deviceName " +
            "FROM heart_rate_records " +
            "WHERE sessionId IN (:sessionIds) AND recordType = 'SLEEP' " +
            "ORDER BY sessionId ASC, beatsPerMinute ASC, timestampMs ASC, sourceRecordId ASC",
    )
    suspend fun getSleepHrSamplesForSessions(sessionIds: List<String>): List<HeartRateRecordEntity>

    @Query(
        "SELECT sessionId, beatsPerMinute " +
            "FROM heart_rate_records " +
            "WHERE sessionId IN (:sessionIds) AND recordType = 'SLEEP' " +
            "ORDER BY sessionId ASC, beatsPerMinute ASC, timestampMs ASC, sourceRecordId ASC",
    )
    suspend fun getSleepHrProjectionForSessions(sessionIds: List<String>): List<SleepHrSample>

    @Query("SELECT MIN(timestampMs) FROM heart_rate_records")
    fun observeEarliestHrTime(): Flow<Long?>

    // PERF-005/WP-23: dashboard day-summary observable -- min/max/avg/count computed in SQL, so a
    // 5,000-row ingest batch invalidating this Flow re-runs a single-row aggregate instead of
    // re-materializing and re-mapping every row in the day (up to 86k at 1 Hz). `WHERE sampleCount > 0`
    // in subquery makes SQLite return zero rows (not one row of NULLs) when the range is empty, so Room maps
    // that to `null` naturally for the nullable single-row return type.
    @Query(
        "SELECT minBpm, maxBpm, avgBpm, sampleCount FROM (" +
            "SELECT MIN(beatsPerMinute) AS minBpm, MAX(beatsPerMinute) AS maxBpm, " +
            "AVG(beatsPerMinute) AS avgBpm, COUNT(*) AS sampleCount " +
            "FROM heart_rate_records " +
            "WHERE timestampMs >= :startMs AND timestampMs < :endMs" +
            ") WHERE sampleCount > 0",
    )
    fun observeAggregateByTimeRange(
        startMs: Long,
        endMs: Long,
    ): Flow<HrRangeAggregate?>

    // PERF-006/WP-21: SQL-side 1-minute bucketing for the everyday-HR load calculator, replacing a
    // full-day `SELECT *` (up to 86k rows/day at 1 Hz) re-bucketed in Kotlin. The plausibility
    // filter (30-230 bpm) mirrors EverydayHeartRateLoadCalculator's former Kotlin-side filter --
    // moved here so implausible samples never enter a bucket's sum/count, identical to filtering
    // before bucketing. `dayEndMs` is exclusive, matching getByTimeRange's callers' day-window
    // convention (dayMidnightMs .. nextDayMidnightMs). Ascending `ORDER BY` matters: the calculator
    // accumulates TRIMP via floating-point `+=`, which is not strictly order-independent, and the
    // Kotlin bucketing this replaces always processed buckets in ascending index order.
    @Query(
        "SELECT (timestampMs - :dayStartMs) / 60000 AS bucketIndex, " +
            "AVG(beatsPerMinute) AS avgBpm, COUNT(*) AS sampleCount " +
            "FROM heart_rate_records " +
            "WHERE timestampMs >= :dayStartMs AND timestampMs < :dayEndMs " +
            "AND beatsPerMinute BETWEEN 30 AND 230 " +
            "GROUP BY bucketIndex " +
            "ORDER BY bucketIndex ASC",
    )
    suspend fun getMinuteBuckets(
        dayStartMs: Long,
        dayEndMs: Long,
    ): List<HrMinuteBucketRow>
}
