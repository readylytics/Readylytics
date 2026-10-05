package app.readylytics.health.core.databaseschema.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.readylytics.health.core.databaseschema.data.local.entity.StepRecordEntity

/**
 * WP-18 staged-scan reconciliation for `step_records`: the anti-join pair that finds and prunes
 * rows this run's completed scan never staged. Split out of [StepRecordDao] -- which owns the
 * record's own CRUD, retention and backup paging -- so neither interface crosses detekt's
 * `TooManyFunctions` threshold, the same structural split already used for
 * [Vo2MaxRecordDao]/[Vo2MaxScanReconciliationDao]. [StepRecordDao] extends this interface, so
 * callers keep using the single `StepRecordDao` type unchanged.
 */
interface StepRecordScanReconciliationDao {
    @Query(
        "SELECT MIN(startTime) AS minMs, MAX(endTime) AS maxMs FROM step_records " +
            "WHERE startTime >= :startMs AND endTime <= :endMs " +
            "AND id NOT IN (" +
            "  SELECT sourceId FROM scan_seen_ids " +
            "  WHERE runId = :runId AND chunkId = :chunkId AND recordType = :recordType)",
    )
    suspend fun boundsOfUnstagedRecords(
        startMs: Long,
        endMs: Long,
        runId: String,
        chunkId: String,
        recordType: String,
    ): StagedDeletionBounds

    @Query(
        "DELETE FROM step_records " +
            "WHERE startTime >= :startMs AND endTime <= :endMs " +
            "AND id NOT IN (" +
            "  SELECT sourceId FROM scan_seen_ids " +
            "  WHERE runId = :runId AND chunkId = :chunkId AND recordType = :recordType)",
    )
    suspend fun deleteRecordsNotStaged(
        startMs: Long,
        endMs: Long,
        runId: String,
        chunkId: String,
        recordType: String,
    ): Int
}

@Dao
interface StepRecordDao : StepRecordScanReconciliationDao {
    @Upsert
    suspend fun upsertAll(records: List<StepRecordEntity>)


    @Query(
        "SELECT * FROM step_records " +
            "WHERE startTime >= :fromMs AND (" +
            "  startTime > :afterTs OR " +
            "  (startTime = :afterTs AND id > :afterId)" +
            ") " +
            "ORDER BY startTime ASC, id ASC " +
            "LIMIT :limit",
    )
    suspend fun pageAfter(
        fromMs: Long,
        afterTs: Long,
        afterId: String,
        limit: Int,
    ): List<StepRecordEntity>

    @Query("SELECT * FROM step_records WHERE id = :id")
    suspend fun getById(id: String): StepRecordEntity?

    @Query("DELETE FROM step_records WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM step_records WHERE startTime < :beforeMs")
    suspend fun deleteBeforeTimestamp(beforeMs: Long): Int

    @Query("SELECT COUNT(*) FROM step_records")
    suspend fun count(): Int

    @Query("DELETE FROM step_records")
    suspend fun deleteAll(): Int

    @Query("SELECT * FROM step_records WHERE startTime >= :startMs AND endTime <= :endMs ORDER BY startTime ASC")
    suspend fun getBetween(startMs: Long, endMs: Long): List<StepRecordEntity>

    @Query("SELECT * FROM step_records WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<StepRecordEntity>

    @Query("DELETE FROM step_records WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>): Int
}
