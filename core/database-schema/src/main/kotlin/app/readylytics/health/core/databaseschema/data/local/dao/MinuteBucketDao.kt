package app.readylytics.health.core.databaseschema.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.readylytics.health.core.databaseschema.data.local.entity.HrMinuteBucketEntity
import app.readylytics.health.core.model.domain.model.HrMinuteBucketRow

// R2-UI-002: retention/pruning/backup-bookkeeping queries live in the sibling
// MinuteBucketMaintenanceDao (same table, same package) -- split out so this interface stays the
// "core" warm-tier read/write surface scoring and UI reconstruction actually depend on, and so
// neither interface trips detekt's TooManyFunctions threshold.
@Dao
interface MinuteBucketDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBuckets(buckets: List<HrMinuteBucketEntity>)

    // Weighted average across all (recordType, sessionId) slices of a minute is exactly the plain
    // AVG over the minute's raw samples, so the everyday-HR load calculator sees the same value
    // whether it reads the hot tier (HeartRateDao.getMinuteBuckets) or the warm tier.
    @Query(
        "SELECT (bucketStartMs - :dayStartMs) / 60000 AS bucketIndex, " +
            "SUM(avgBpm * sampleCount) / SUM(sampleCount) AS avgBpm, " +
            "SUM(sampleCount) AS sampleCount " +
            "FROM hr_minute_buckets " +
            "WHERE bucketStartMs >= :dayStartMs AND bucketEndMs <= :dayEndMs " +
            "AND avgBpm BETWEEN 30 AND 230 " +
            "GROUP BY bucketIndex " +
            "ORDER BY bucketIndex ASC",
    )
    suspend fun getMinuteBuckets(
        dayStartMs: Long,
        dayEndMs: Long,
    ): List<HrMinuteBucketRow>

    @Query(
        "SELECT * FROM hr_minute_buckets " +
            "WHERE recordType = :recordType AND sessionId = :sessionId " +
            "ORDER BY bucketStartMs ASC",
    )
    suspend fun getBucketsForSession(
        recordType: String,
        sessionId: String,
    ): List<HrMinuteBucketEntity>

    // R2-UI-002: warm-tier equivalent of HeartRateDao.getByTimeRange -- unlike getBucketsForSession
    // (recordType + sessionId keyed), this is a plain overlap query across every bucket type, for
    // callers (HeartRateRepository) that need whatever warm-tier data exists in a time window
    // regardless of which session/record type it came from.
    @Query(
        "SELECT * FROM hr_minute_buckets WHERE bucketStartMs <= :endMs AND bucketEndMs >= :startMs " +
            "ORDER BY bucketStartMs ASC",
    )
    suspend fun getBucketsInTimeRange(startMs: Long, endMs: Long): List<HrMinuteBucketEntity>

    @Query("DELETE FROM hr_minute_buckets WHERE bucketStartMs >= :startMs AND bucketStartMs < :endMs")
    suspend fun deleteInRange(startMs: Long, endMs: Long)

    /**
     * WP-17: drops every visible bucket slice of exactly the listed minutes. `upsertBuckets` only
     * replaces rows whose full `(bucketStartMs, recordType, sessionId, deviceName)` key matches, so
     * re-publishing a minute whose previous generation had a different session/device slice would
     * otherwise leave that stale slice behind at the old `generation` -- one minute carrying a mix
     * of generations. Publication calls this for the minutes it is about to republish, inside the
     * same transaction as the upsert.
     */
    @Query("DELETE FROM hr_minute_buckets WHERE bucketStartMs IN (:bucketStartMs)")
    suspend fun deleteBucketsForMinutes(bucketStartMs: List<Long>)

    // ---------------------------------------------------------------------------------------------
    // WP-17 Step 3: warm-side tier-visibility predicate -- the exact mirror of the raw-side
    // predicate on `HeartRateDao.getVisible*`. Together they guarantee that for any minute a reader
    // sees either its raw rows or its warm bucket slices, never both, and within the warm tier only
    // ONE generation (`minute_coverage.visibleGeneration`), never a superseded one.
    //
    // A bucket is visible when either:
    //  * its minute has a committed `WARM`/`LEGACY_WARM` coverage row AND the bucket's own
    //    `generation` equals that row's `visibleGeneration` (a `HOT` coverage row therefore hides
    //    every warm projection of that minute, and a superseded generation stays invisible even if
    //    a crash left its rows behind); or
    //  * its minute has NO coverage row at all AND no raw row either. That is the
    //    "missing metadata permits warm-only compatibility" case from the T2 policy: migration
    //    21->22 and old-archive restore both backfill `LEGACY_WARM` coverage for every pre-existing
    //    bucket, so this branch is defence-in-depth for a bucket that somehow has no ledger entry.
    //    When such a minute has raw rows too, the overlap is explicitly unresolved under the T2
    //    policy and is resolved in favour of the raw tier here (never summed or concatenated), so
    //    the raw-side predicate -- which lets coverage-less minutes through -- stays authoritative.
    //
    // The two predicates are a TOTAL partition of the minute space, not merely a disjoint pair. The
    // one hole -- coverage committed as `WARM`/`LEGACY_WARM` at a generation that has no bucket
    // slice, e.g. a partially applied restore -- is closed on the RAW side: `HeartRateDao`'s
    // predicate carries a matching `NOT EXISTS (... hr_minute_buckets b2 ...)` fallback that serves
    // that minute's raw evidence rather than letting it vanish from both readers. See that comment
    // block; do not weaken either half without the other.
    // ---------------------------------------------------------------------------------------------

    /** Tier-authoritative equivalent of [getMinuteBuckets] (same weighted-average semantics). */
    @Query(
        "SELECT (b.bucketStartMs - :dayStartMs) / 60000 AS bucketIndex, " +
            "SUM(b.avgBpm * b.sampleCount) / SUM(b.sampleCount) AS avgBpm, " +
            "SUM(b.sampleCount) AS sampleCount " +
            "FROM hr_minute_buckets b " +
            "LEFT JOIN minute_coverage c ON c.bucketStartMs = b.bucketStartMs " +
            "WHERE b.bucketStartMs >= :dayStartMs AND b.bucketEndMs <= :dayEndMs " +
            "AND b.avgBpm BETWEEN 30 AND 230 " +
            "AND ((c.tier IN ('WARM', 'LEGACY_WARM') AND c.visibleGeneration = b.generation) " +
            "OR (c.bucketStartMs IS NULL AND NOT EXISTS (" +
            "SELECT 1 FROM heart_rate_records h WHERE h.timestampMs >= b.bucketStartMs " +
            "AND h.timestampMs < b.bucketStartMs + 60000))) " +
            "GROUP BY bucketIndex " +
            "ORDER BY bucketIndex ASC",
    )
    suspend fun getVisibleMinuteBuckets(
        dayStartMs: Long,
        dayEndMs: Long,
    ): List<HrMinuteBucketRow>

    /** Tier-authoritative equivalent of [getBucketsForSession]. */
    @Query(
        "SELECT b.* FROM hr_minute_buckets b " +
            "LEFT JOIN minute_coverage c ON c.bucketStartMs = b.bucketStartMs " +
            "WHERE b.recordType = :recordType AND b.sessionId = :sessionId " +
            "AND ((c.tier IN ('WARM', 'LEGACY_WARM') AND c.visibleGeneration = b.generation) " +
            "OR (c.bucketStartMs IS NULL AND NOT EXISTS (" +
            "SELECT 1 FROM heart_rate_records h WHERE h.timestampMs >= b.bucketStartMs " +
            "AND h.timestampMs < b.bucketStartMs + 60000))) " +
            "ORDER BY b.bucketStartMs ASC",
    )
    suspend fun getVisibleBucketsForSession(
        recordType: String,
        sessionId: String,
    ): List<HrMinuteBucketEntity>

    /** Tier-authoritative equivalent of [getBucketsInTimeRange] (same overlap semantics). */
    @Query(
        "SELECT b.* FROM hr_minute_buckets b " +
            "LEFT JOIN minute_coverage c ON c.bucketStartMs = b.bucketStartMs " +
            "WHERE b.bucketStartMs <= :endMs AND b.bucketEndMs >= :startMs " +
            "AND ((c.tier IN ('WARM', 'LEGACY_WARM') AND c.visibleGeneration = b.generation) " +
            "OR (c.bucketStartMs IS NULL AND NOT EXISTS (" +
            "SELECT 1 FROM heart_rate_records h WHERE h.timestampMs >= b.bucketStartMs " +
            "AND h.timestampMs < b.bucketStartMs + 60000))) " +
            "ORDER BY b.bucketStartMs ASC",
    )
    suspend fun getVisibleBucketsInTimeRange(
        startMs: Long,
        endMs: Long,
    ): List<HrMinuteBucketEntity>

    /**
     * Visible bucket slices of exactly the minutes in `[startMs, endMs)`, keyed on
     * `bucketStartMs` rather than on overlap -- the form the full-range relink pass compares its
     * freshly derived projection against, so a boundary minute is never pulled in twice.
     */
    @Query(
        "SELECT b.* FROM hr_minute_buckets b " +
            "LEFT JOIN minute_coverage c ON c.bucketStartMs = b.bucketStartMs " +
            "WHERE b.bucketStartMs >= :startMs AND b.bucketStartMs < :endMs " +
            "AND ((c.tier IN ('WARM', 'LEGACY_WARM') AND c.visibleGeneration = b.generation) " +
            "OR (c.bucketStartMs IS NULL AND NOT EXISTS (" +
            "SELECT 1 FROM heart_rate_records h WHERE h.timestampMs >= b.bucketStartMs " +
            "AND h.timestampMs < b.bucketStartMs + 60000))) " +
            "ORDER BY b.bucketStartMs ASC, b.recordType ASC, b.sessionId ASC, b.deviceName ASC",
    )
    suspend fun getVisibleBucketsInMinuteRange(
        startMs: Long,
        endMs: Long,
    ): List<HrMinuteBucketEntity>
}
