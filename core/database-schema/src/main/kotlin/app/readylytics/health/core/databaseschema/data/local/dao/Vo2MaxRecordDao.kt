package app.readylytics.health.core.databaseschema.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.readylytics.health.core.databaseschema.data.local.entity.Vo2MaxRecordEntity

/**
 * WP-18 staged-scan reconciliation for `vo2_max_records`: the anti-join pair that finds and prunes
 * rows this run's completed scan never staged. Split out of [Vo2MaxRecordDao] -- which owns the
 * record's own CRUD, retention and backup paging -- so neither interface crosses detekt's
 * `TooManyFunctions` threshold, the same structural split already used for
 * [SourceRecordDao]/[SourceRecordResolutionDao]. [Vo2MaxRecordDao] extends this interface, so
 * callers keep using the single `Vo2MaxRecordDao` type unchanged.
 */
interface Vo2MaxScanReconciliationDao {
    @Query(
        "SELECT MIN(timestampMs) AS minMs, MAX(timestampMs) AS maxMs FROM vo2_max_records " +
            "WHERE timestampMs >= :startMs AND timestampMs <= :endMs " +
            "AND id NOT IN (" +
            "  SELECT sourceId FROM scan_seen_ids " +
            "  WHERE runId = :runId AND chunkId = :chunkId AND recordType = :recordType)",
    )
    suspend fun boundsOfUnstagedRows(
        startMs: Long,
        endMs: Long,
        runId: String,
        chunkId: String,
        recordType: String,
    ): StagedDeletionBounds

    @Query(
        "DELETE FROM vo2_max_records " +
            "WHERE timestampMs >= :startMs AND timestampMs <= :endMs " +
            "AND id NOT IN (" +
            "  SELECT sourceId FROM scan_seen_ids " +
            "  WHERE runId = :runId AND chunkId = :chunkId AND recordType = :recordType)",
    )
    suspend fun deleteRowsNotStaged(
        startMs: Long,
        endMs: Long,
        runId: String,
        chunkId: String,
        recordType: String,
    ): Int
}

@Dao
interface Vo2MaxRecordDao : Vo2MaxScanReconciliationDao {
    @Upsert
    suspend fun upsertAll(records: List<Vo2MaxRecordEntity>)

    @Query(
        "SELECT * FROM vo2_max_records WHERE timestampMs >= :startMs AND timestampMs < :endMs " +
            "ORDER BY timestampMs DESC",
    )
    suspend fun getByTimeRange(
        startMs: Long,
        endMs: Long,
    ): List<Vo2MaxRecordEntity>

    @Query(
        "SELECT * FROM vo2_max_records WHERE timestampMs >= :minTimestampMs AND timestampMs < :endExclusiveMs " +
            "ORDER BY timestampMs DESC, id DESC LIMIT 1",
    )
    suspend fun getLatestInRange(minTimestampMs: Long, endExclusiveMs: Long): Vo2MaxRecordEntity?

    @Query("DELETE FROM vo2_max_records WHERE timestampMs < :cutoffMs")
    suspend fun deleteBefore(cutoffMs: Long): Int

    @Query("SELECT * FROM vo2_max_records WHERE id = :id")
    suspend fun getById(id: String): Vo2MaxRecordEntity?

    @Query("DELETE FROM vo2_max_records WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query(
        "SELECT * FROM vo2_max_records " +
            "WHERE timestampMs >= :fromMs AND (" +
            "  timestampMs > :afterTs OR " +
            "  (timestampMs = :afterTs AND id > :afterId)" +
            ") " +
            "ORDER BY timestampMs ASC, id ASC " +
            "LIMIT :limit",
    )
    suspend fun pageAfter(
        fromMs: Long,
        afterTs: Long,
        afterId: String,
        limit: Int,
    ): List<Vo2MaxRecordEntity>

    @Query("SELECT COUNT(*) FROM vo2_max_records")
    suspend fun count(): Int

    /**
     * Whole-table clear. Restore (`RestoreDatabaseOperations.clearDatabaseTablesChildBeforeParent`)
     * needs "empty this table", which is a different operation from the retention range delete
     * [deleteBefore] -- expressing it as `deleteBefore(Long.MAX_VALUE)` would leave a row at
     * exactly `Long.MAX_VALUE` behind. Not used by any sync path: ingestion stays upsert-keyed and
     * never blanket-deletes.
     */
    @Query("DELETE FROM vo2_max_records")
    suspend fun deleteAll(): Int
}
