package app.readylytics.health.core.databaseschema.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.readylytics.health.core.databaseschema.data.local.entity.HrSourceMinuteContributionEntity
import app.readylytics.health.core.databaseschema.data.local.entity.MinuteCoverageEntity

/**
 * WP-17/OD-1 publication surface for the per-minute visible-coverage ledger (`minute_coverage`)
 * and the per-source minute evidence it is derived from (`hr_source_minute_contributions`).
 *
 * Backup/restore bookkeeping over the same two tables (keyset paging, counts, table-wide deletes)
 * lives in the sibling [MinuteCoverageMaintenanceDao] -- split for exactly the reason
 * [MinuteBucketDao]/[MinuteBucketMaintenanceDao] are split: this interface stays the surface the
 * publication path depends on, and neither interface trips detekt's `TooManyFunctions` threshold.
 */
@Dao
interface MinuteCoverageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCoverage(coverage: List<MinuteCoverageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertContributions(contributions: List<HrSourceMinuteContributionEntity>)

    @Query(
        "SELECT * FROM minute_coverage " +
            "WHERE bucketStartMs >= :startMs AND bucketStartMs < :endMs " +
            "ORDER BY bucketStartMs ASC",
    )
    suspend fun getCoverageInRange(
        startMs: Long,
        endMs: Long,
    ): List<MinuteCoverageEntity>

    /**
     * OD-1: minutes whose visible coverage is still the pre-v22 approximate warm projection
     * (`quality = 'LEGACY_UNKNOWN'`). An ordinary hot→warm rollup is NOT a complete authorized
     * interval refresh, so it must leave these minutes alone -- neither replacing their coverage
     * with `SOURCE_BACKED` nor mixing source-backed buckets into them (which would produce the
     * concatenated minute OD-1 forbids). Callers quarantine the raw evidence instead.
     */
    @Query(
        "SELECT bucketStartMs FROM minute_coverage " +
            "WHERE bucketStartMs >= :startMs AND bucketStartMs < :endMs " +
            "AND quality = 'LEGACY_UNKNOWN' ORDER BY bucketStartMs ASC",
    )
    suspend fun getLegacyMinutesInRange(
        startMs: Long,
        endMs: Long,
    ): List<Long>

    @Query(
        "SELECT * FROM hr_source_minute_contributions WHERE bucketStartMs = :bucketStartMs " +
            "ORDER BY sourceRecordRef ASC, generation ASC",
    )
    suspend fun getContributionsForMinute(bucketStartMs: Long): List<HrSourceMinuteContributionEntity>

    @Query("DELETE FROM minute_coverage WHERE bucketStartMs >= :startMs AND bucketStartMs < :endMs")
    suspend fun deleteCoverageInRange(
        startMs: Long,
        endMs: Long,
    )

    @Query(
        "DELETE FROM hr_source_minute_contributions " +
            "WHERE bucketStartMs >= :startMs AND bucketStartMs < :endMs",
    )
    suspend fun deleteContributionsInRange(
        startMs: Long,
        endMs: Long,
    )

    /**
     * Drops every generation's contributions for exactly the listed minutes. `generation` is part
     * of the contribution primary key, so a re-publication of the same minute would otherwise
     * append a second row instead of replacing the superseded one (unbounded growth, and
     * double-counting for any consumer that sums a minute without filtering on
     * `minute_coverage.visibleGeneration`). Publication calls this in the same transaction as the
     * insert, so a minute is never visible with two generations of evidence.
     */
    @Query("DELETE FROM hr_source_minute_contributions WHERE bucketStartMs IN (:bucketStartMs)")
    suspend fun deleteContributionsForMinutes(bucketStartMs: List<Long>)
}
