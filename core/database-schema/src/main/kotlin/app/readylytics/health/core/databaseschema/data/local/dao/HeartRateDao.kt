package app.readylytics.health.core.databaseschema.data.local.dao


import androidx.room.Dao
import androidx.room.MapColumn
import androidx.room.Query
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.model.domain.model.HrMinuteBucketRow
import app.readylytics.health.core.model.domain.model.HrRangeAggregate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

@Dao
interface HeartRateDao {
    @Query(
        "SELECT * FROM heart_rate_records " +
            "WHERE timestampMs >= :fromMs ORDER BY timestampMs ASC, sourceRecordRef ASC",
    )
    suspend fun getSince(fromMs: Long): List<HeartRateRecordEntity>


    @Query(
        "SELECT * FROM heart_rate_records " +
            "WHERE timestampMs >= :fromMs AND (" +
            "  timestampMs > :afterTs OR " +
            "  (timestampMs = :afterTs AND sourceRecordRef > :afterRef)" +
            ") " +
            "ORDER BY timestampMs ASC, sourceRecordRef ASC " +
            "LIMIT :limit",
    )
    suspend fun pageAfter(
        fromMs: Long,
        afterTs: Long,
        afterRef: Long,
        limit: Int,
    ): List<HeartRateRecordEntity>

    @Query(
        "SELECT * FROM heart_rate_records " +
            "WHERE timestampMs >= :startMs AND timestampMs <= :endMs " +
            "AND (timestampMs > :lastTimestampMs OR " +
            "(timestampMs = :lastTimestampMs AND sourceRecordRef > :lastSourceRecordRef)) " +
            "ORDER BY timestampMs ASC, sourceRecordRef ASC LIMIT :limit",
    )
    suspend fun getKeysetPage(
        startMs: Long,
        endMs: Long,
        lastTimestampMs: Long,
        lastSourceRecordRef: Long,
        limit: Int,
    ): List<HeartRateRecordEntity>

    @Query(
        "SELECT CAST(ROUND(AVG(beatsPerMinute)) AS INTEGER) FROM heart_rate_records " +
            "WHERE recordType = 'SLEEP' AND sessionId = :sessionId " +
            "AND beatsPerMinute BETWEEN 30 AND 230",
    )
    suspend fun getAvgSleepHr(sessionId: String): Int?

    @Query(
        "SELECT sessionId, CAST(ROUND(AVG(beatsPerMinute)) AS INTEGER) AS avgHr FROM heart_rate_records " +
            "WHERE recordType = 'SLEEP' AND sessionId IN (:sessionIds) " +
            "AND beatsPerMinute BETWEEN 30 AND 230 " +
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
            "AND beatsPerMinute BETWEEN 30 AND 230 " +
            "GROUP BY sessionId",
    )
    suspend fun getAvgSleepHrPerSession(fromMs: Long): List<Int>

    @Query(
        "SELECT beatsPerMinute FROM heart_rate_records " +
            "WHERE sessionId = :sessionId AND recordType = 'SLEEP' " +
            "AND beatsPerMinute BETWEEN 30 AND 230 " +
            "ORDER BY beatsPerMinute ASC, timestampMs ASC, sourceRecordRef ASC",
    )
    suspend fun getSleepHrSamplesForSession(sessionId: String): List<Int>

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

    // OD-3 (Phase 1 plan, 2026-08-31): deliberately unfiltered — this backs the raw HR timeline
    // chart, which shows sensor data as-is rather than hiding implausible spikes. Every
    // scoring-facing query in this file applies the 30-230 plausibility predicate; this is the
    // documented exception.
    fun observeSleepHrTimelineForSession(sessionId: String): Flow<List<HeartRateRecordEntity>> =
        _observeSleepHrTimelineForSession(sessionId).distinctUntilChanged()

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
        "SELECT * FROM heart_rate_records WHERE timestampMs >= :startMs AND timestampMs <= :endMs " +
            "ORDER BY timestampMs ASC, sourceRecordRef ASC",
    )
    suspend fun getByTimeRange(
        startMs: Long,
        endMs: Long,
    ): List<HeartRateRecordEntity>

    // DB-001: recordType-filtered variant of getByTimeRange, backed by index_hr_v10_type_timestamp
    // (recordType, timestampMs) -- callers that only need one record type (e.g. exercise-HR for
    // workout metrics) no longer pull every sleep/resting sample in the range into memory just to
    // discard it with a Kotlin `.filter`.
    @Query(
        "SELECT * FROM heart_rate_records " +
            "WHERE recordType = :recordType AND timestampMs >= :startMs AND timestampMs <= :endMs " +
            "ORDER BY timestampMs ASC, sourceRecordRef ASC",
    )
    suspend fun getByTypeAndTimeRange(
        recordType: String,
        startMs: Long,
        endMs: Long,
    ): List<HeartRateRecordEntity>

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

    // Conflict-targeted UPSERT on the natural unique key (sourceRecordRef, timestampMs): updates
    // mutable columns (recordType/sessionId/deviceName) in place and preserves rowId — unlike
    // SQLite REPLACE, which deletes+reinserts and rotates rowId on every re-upsert. The WHERE
    // predicate makes an identical re-ingest a near-no-op (SQLite changes() = 0).
    @Query(
        "INSERT INTO heart_rate_records " +
            "(sourceRecordRef, timestampMs, beatsPerMinute, recordType, sessionId, deviceName) " +
            "VALUES (:sourceRecordRef, :timestampMs, :beatsPerMinute, :recordType, :sessionId, :deviceName) " +
            "ON CONFLICT(sourceRecordRef, timestampMs) DO UPDATE SET " +
            "recordType = excluded.recordType, " +
            "sessionId = excluded.sessionId, " +
            "deviceName = excluded.deviceName " +
            "WHERE (recordType IS NOT excluded.recordType OR " +
            "sessionId IS NOT excluded.sessionId OR deviceName IS NOT excluded.deviceName)",
    )
    suspend fun conflictTargetedUpsert(
        sourceRecordRef: Long,
        timestampMs: Long,
        beatsPerMinute: Int,
        recordType: String,
        sessionId: String?,
        deviceName: String?,
    ): Long

    suspend fun upsertAll(records: List<HeartRateRecordEntity>) {
        for (record in records) {
            conflictTargetedUpsert(
                sourceRecordRef = record.sourceRecordRef,
                timestampMs = record.timestampMs,
                beatsPerMinute = record.beatsPerMinute,
                recordType = record.recordType,
                sessionId = record.sessionId,
                deviceName = record.deviceName,
            )
        }
    }

    @Query("DELETE FROM heart_rate_records WHERE timestampMs < :beforeMs")
    suspend fun deleteBeforeTimestamp(beforeMs: Long): Int

    // R2-DB-004: day-chunk-bounded delete for DataRollupManager -- deletes every raw row in
    // [fromMs, toMs), plausible or not (matching deleteBeforeTimestamp's unconditional contract),
    // scoped to one rollup day-chunk instead of everything before the cutoff at once.
    @Query("DELETE FROM heart_rate_records WHERE timestampMs >= :fromMs AND timestampMs < :toMs")
    suspend fun deleteInRange(
        fromMs: Long,
        toMs: Long,
    ): Int

    // DB-002: keyset-bounded delete for RetentionCleanup -- deletes at most `limit` of the oldest
    // rows before `beforeMs` per call, so a large first-time cleanup opens many bounded
    // transactions instead of one unbounded delete (WAL growth).
    @Query(
        "DELETE FROM heart_rate_records WHERE rowId IN (" +
            "SELECT rowId FROM heart_rate_records WHERE timestampMs < :beforeMs " +
            "ORDER BY timestampMs ASC LIMIT :limit" +
            ")",
    )
    suspend fun deleteBeforeTimestampBatch(
        beforeMs: Long,
        limit: Int,
    ): Int

    @Query("DELETE FROM heart_rate_records WHERE sourceRecordRef = :sourceRecordRef")
    suspend fun deleteByRef(sourceRecordRef: Long): Int

    @Query("SELECT * FROM heart_rate_records WHERE sourceRecordRef = :sourceRecordRef")
    suspend fun getByRef(sourceRecordRef: Long): HeartRateRecordEntity?

    @Query(
        "SELECT * FROM heart_rate_records " +
            "WHERE sourceRecordRef = :sourceRecordRef " +
            "ORDER BY timestampMs ASC, sourceRecordRef ASC",
    )
    suspend fun getBySourceRecordRef(sourceRecordRef: Long): List<HeartRateRecordEntity>

    @Query(
        "DELETE FROM heart_rate_records WHERE sourceRecordRef = :sourceRecordRef",
    )
    suspend fun deleteBySourceRecordRef(sourceRecordRef: Long): Int

    @Query("SELECT COUNT(*) FROM heart_rate_records")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM heart_rate_records WHERE timestampMs >= :startMs AND timestampMs <= :endMs")
    suspend fun countInRange(startMs: Long, endMs: Long): Int

    @Query("DELETE FROM heart_rate_records")
    suspend fun deleteAll(): Int

    @Query("SELECT DISTINCT deviceName FROM heart_rate_records WHERE deviceName IS NOT NULL AND deviceName != ''")
    suspend fun getDistinctDeviceNames(): List<String>

    @Query(
        "DELETE FROM heart_rate_records " +
            "WHERE timestampMs >= :fromMs AND timestampMs < :toMs " +
            "AND (deviceName != :deviceName OR deviceName IS NULL)",
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
        "SELECT rowId, sourceRecordRef, sessionId, recordType, beatsPerMinute, timestampMs, deviceName " +
            "FROM heart_rate_records " +
            "WHERE sessionId IN (:sessionIds) AND recordType = 'SLEEP' " +
            "AND beatsPerMinute BETWEEN 30 AND 230 " +
            "ORDER BY sessionId ASC, beatsPerMinute ASC, timestampMs ASC, sourceRecordRef ASC",
    )
    suspend fun getSleepHrSamplesForSessions(sessionIds: List<String>): List<HeartRateRecordEntity>

    @Query(
        "SELECT sessionId, beatsPerMinute " +
            "FROM heart_rate_records " +
            "WHERE sessionId IN (:sessionIds) AND recordType = 'SLEEP' " +
            "AND beatsPerMinute BETWEEN 30 AND 230 " +
            "ORDER BY sessionId ASC, beatsPerMinute ASC, timestampMs ASC, sourceRecordRef ASC",
    )
    suspend fun getSleepHrProjectionForSessions(sessionIds: List<String>): List<SleepHrSample>

    @Query("SELECT MIN(timestampMs) FROM heart_rate_records")
    fun observeEarliestHrTime(): Flow<Long?>

    // OD-3 (Phase 1 plan, 2026-08-31): deliberately unfiltered — this backs the raw HR timeline
    // chart, which shows sensor data as-is rather than hiding implausible spikes. Every
    // scoring-facing query in this file applies the 30-230 plausibility predicate; this is the
    // documented exception.
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

    // R2-DB-004: feeds the Kotlin-side rollup aggregator (MinuteBucketAggregator.kt) — SQLite has
    // no PERCENTILE_CONT, so the five-percentile warm-tier sketch is computed in Kotlin, not SQL.
    // Scoped to [fromMs, toMs) -- one rollup day-chunk -- rather than everything before the
    // cutoff, so a large historical backlog is never read into memory in a single pass (this
    // table is the same high-volume outlier RetentionCleanup/DB-002 batches for). Ordered by
    // (recordType, sessionId, timestampMs) so a single linear grouping pass produces buckets in
    // ascending-timestamp order per (recordType, sessionId) key.
    @Query(
        "SELECT * FROM heart_rate_records " +
            "WHERE timestampMs >= :fromMs AND timestampMs < :toMs AND beatsPerMinute BETWEEN 30 AND 230 " +
            "ORDER BY recordType ASC, sessionId ASC, timestampMs ASC",
    )
    suspend fun getPlausibleSamplesInRangeForRollup(
        fromMs: Long,
        toMs: Long,
    ): List<HeartRateRecordEntity>

    // R2-DB-004: anchors DataRollupManager's day-chunk loop to wherever raw data actually starts,
    // and (re-queried after each chunk) to the next day containing data.
    @Query("SELECT MIN(timestampMs) FROM heart_rate_records")
    suspend fun getEarliestTimestampMs(): Long?
}
