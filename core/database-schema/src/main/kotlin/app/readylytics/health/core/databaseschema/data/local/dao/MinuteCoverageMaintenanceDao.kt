package app.readylytics.health.core.databaseschema.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import app.readylytics.health.core.databaseschema.data.local.entity.HrSourceMinuteContributionEntity
import app.readylytics.health.core.databaseschema.data.local.entity.MinuteCoverageEntity

/**
 * Backup/restore bookkeeping half of the WP-17 coverage tables, split out of [MinuteCoverageDao]
 * for the same reason [MinuteBucketMaintenanceDao] is split out of [MinuteBucketDao]: keep the
 * publication surface small and both interfaces under detekt's `TooManyFunctions` threshold.
 * Consumers are the local backup/restore path only (`BackupStreamWriter`, `RestoreBatchLoader`,
 * `RestoreDatabaseOperations`).
 */
@Dao
interface MinuteCoverageMaintenanceDao {
    @Query(
        "SELECT * FROM minute_coverage WHERE bucketStartMs > :afterTs " +
            "ORDER BY bucketStartMs ASC LIMIT :limit",
    )
    suspend fun pageCoverageAfter(
        afterTs: Long,
        limit: Int,
    ): List<MinuteCoverageEntity>

    /**
     * Keyset pagination over the FULL composite primary key
     * `(bucketStartMs, sourceRecordRef, generation)`. `bucketStartMs` alone is not unique in this
     * table: paging on it would silently skip every remaining row of a `bucketStartMs` group once
     * a page boundary landed inside that group, and the manifest's `COUNT(*)` would then mismatch
     * and abort the whole restore. Same OR-chain shape as [MinuteBucketMaintenanceDao.pageAfter].
     */
    @Query(
        "SELECT * FROM hr_source_minute_contributions WHERE (" +
            "  bucketStartMs > :afterTs OR " +
            "  (bucketStartMs = :afterTs AND sourceRecordRef > :afterSourceRecordRef) OR " +
            "  (bucketStartMs = :afterTs AND sourceRecordRef = :afterSourceRecordRef AND " +
            "   generation > :afterGeneration)" +
            ") " +
            "ORDER BY bucketStartMs ASC, sourceRecordRef ASC, generation ASC " +
            "LIMIT :limit",
    )
    suspend fun pageContributionsAfter(
        afterTs: Long,
        afterSourceRecordRef: Long,
        afterGeneration: Long,
        limit: Int,
    ): List<HrSourceMinuteContributionEntity>

    @Query("SELECT COUNT(*) FROM minute_coverage")
    suspend fun countCoverage(): Int

    @Query("SELECT COUNT(*) FROM hr_source_minute_contributions")
    suspend fun countContributions(): Int

    @Query("DELETE FROM minute_coverage")
    suspend fun deleteAllCoverage(): Int

    @Query("DELETE FROM hr_source_minute_contributions")
    suspend fun deleteAllContributions(): Int
}
