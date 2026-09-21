package app.readylytics.health.core.databaseschema.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import app.readylytics.health.core.databaseschema.data.local.entity.HrSourceMinuteContributionEntity

/**
 * WP-17 Step 3/4 tier-selection and relink query surface over `minute_coverage` /
 * `hr_source_minute_contributions`.
 *
 * Third `@Dao` over the same two tables, split out of [MinuteCoverageDao] (the publication
 * surface) and [MinuteCoverageMaintenanceDao] (backup/restore bookkeeping) for exactly the reason
 * those two are split from each other: each interface stays small enough that none trips detekt's
 * `TooManyFunctions` threshold, and each has one obvious owner. Room supports several `@Dao`
 * interfaces over one table with no extra `@Database` configuration, and adding one changes no
 * entity, so it needs no schema version or migration.
 *
 * Consumers: `AuthoritativeHeartRateReader` (coverage-quality reporting) and `WarmTierRelinker`
 * (the full-range relink pass).
 */
@Dao
interface MinuteCoverageSelectionDao {
    /**
     * The first committed minute at or after [fromMs], or `null` when the ledger holds none.
     * `minute_coverage.bucketStartMs` is a single-column INTEGER primary key, i.e. a SQLite rowid
     * alias, so this is an O(log n) seek with no extra index -- it lets the relink pass skip
     * straight over uncovered stretches (a fresh install's whole history) instead of walking
     * thousands of empty day-chunks. Deliberately unfiltered by `quality`: filtering here would
     * turn the seek into a forward scan, and a chunk with only legacy coverage is a cheap no-op.
     */
    @Query("SELECT MIN(bucketStartMs) FROM minute_coverage WHERE bucketStartMs >= :fromMs")
    suspend fun nextCoveredMinuteAtOrAfter(fromMs: Long): Long?

    /**
     * Every per-source contribution in `[startMs, endMs)` that sits at its own minute's *visible*
     * generation and whose minute is `SOURCE_BACKED`. This is the immutable evidence the full-range
     * relink derives session/type buckets from -- never the previous pass's linked buckets -- so a
     * repeated pass cannot drift by progressively reconstructing its own output.
     *
     * `LEGACY_UNKNOWN` minutes are excluded on purpose: they predate per-source contributions and
     * carry no timestamp/sample evidence, so they cannot be relinked exactly. They are preserved
     * as approximate/unresolved (OD-1) and reported via [countLegacyMinutesInRange].
     */
    @Query(
        "SELECT ct.* FROM hr_source_minute_contributions ct " +
            "JOIN minute_coverage c ON c.bucketStartMs = ct.bucketStartMs " +
            "AND c.visibleGeneration = ct.generation " +
            "WHERE ct.bucketStartMs >= :startMs AND ct.bucketStartMs < :endMs " +
            "AND c.quality = 'SOURCE_BACKED' " +
            "ORDER BY ct.bucketStartMs ASC, ct.sourceRecordRef ASC",
    )
    suspend fun getVisibleSourceBackedContributions(
        startMs: Long,
        endMs: Long,
    ): List<HrSourceMinuteContributionEntity>

    /**
     * `SOURCE_BACKED` minutes in `[startMs, endMs)` whose visible generation has no contribution
     * evidence left at all -- every contributing source record was deleted (Health Connect
     * deletion reconciliation calls `SourceRecordDao.deleteBySourceRecordId`, which drops that
     * source's contributions and cascades its raw rows). Such a minute's warm projection is backed
     * by nothing, so the relink pass retires it rather than keeping a bucket no evidence supports.
     */
    @Query(
        "SELECT c.bucketStartMs FROM minute_coverage c " +
            "WHERE c.bucketStartMs >= :startMs AND c.bucketStartMs < :endMs " +
            "AND c.quality = 'SOURCE_BACKED' " +
            "AND NOT EXISTS (" +
            "SELECT 1 FROM hr_source_minute_contributions ct " +
            "WHERE ct.bucketStartMs = c.bucketStartMs AND ct.generation = c.visibleGeneration) " +
            "ORDER BY c.bucketStartMs ASC",
    )
    suspend fun getEvidencelessSourceBackedMinutes(
        startMs: Long,
        endMs: Long,
    ): List<Long>

    /**
     * Retires the coverage rows of exactly the listed minutes, returning them to "no committed
     * coverage". Paired with `MinuteBucketDao.deleteBucketsForMinutes` inside one transaction, so a
     * minute never survives with coverage but no projection (or the reverse).
     */
    @Query("DELETE FROM minute_coverage WHERE bucketStartMs IN (:bucketStartMs)")
    suspend fun deleteCoverageForMinutes(bucketStartMs: List<Long>)

    /**
     * How many minutes in `[startMs, endMs)` are still the pre-v22 approximate projection. The
     * relink pass cannot resolve these (no per-source evidence exists to reconstruct from), so it
     * reports the count instead of publishing a "corrected" projection on guessed lineage -- the
     * measured size of what only an authorized complete interval refresh can repair.
     */
    @Query(
        "SELECT COUNT(*) FROM minute_coverage " +
            "WHERE bucketStartMs >= :startMs AND bucketStartMs < :endMs " +
            "AND quality = 'LEGACY_UNKNOWN'",
    )
    suspend fun countLegacyMinutesInRange(
        startMs: Long,
        endMs: Long,
    ): Int
}
