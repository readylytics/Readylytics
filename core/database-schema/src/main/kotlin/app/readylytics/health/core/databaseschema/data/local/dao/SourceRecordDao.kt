package app.readylytics.health.core.databaseschema.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.readylytics.health.core.databaseschema.data.local.entity.HealthSourceRecordEntity

interface SourceRecordMaintenanceDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(entities: List<HealthSourceRecordEntity>)

    @Query("SELECT * FROM health_source_records ORDER BY id ASC")
    suspend fun getAll(): List<HealthSourceRecordEntity>

    @Query("SELECT COUNT(*) FROM health_source_records")
    suspend fun count(): Int

    @Query("DELETE FROM health_source_records")
    suspend fun deleteAll(): Int

    @Query("SELECT * FROM health_source_records WHERE id > :afterRef ORDER BY id ASC LIMIT :limit")
    suspend fun pageAfter(afterRef: Long, limit: Int): List<HealthSourceRecordEntity>

    @Query(
        "UPDATE health_source_records " +
            "SET recordStartMs = :recordStartMs, " +
            "recordEndExclusiveMs = :recordEndExclusiveMs, " +
            "metadataState = :metadataState " +
            "WHERE id = :id AND metadataState = 'UNKNOWN'",
    )
    suspend fun updateBackfilledBounds(
        id: Long,
        recordStartMs: Long,
        recordEndExclusiveMs: Long,
        metadataState: String = "CHILD_BOUNDS",
    ): Int
}

/**
 * Single- and bulk-lookup/insert of `health_source_records` rows by `sourceRecordId`. Split out of
 * [SourceRecordDao] -- which owns deletion/authoritative-metadata/paging -- so neither interface
 * crosses detekt's `TooManyFunctions` threshold; PERF-001's bulk `getSourcesByRecordIds` /
 * `insertIgnoreAll` pair pushed the combined interface over it. [SourceRecordDao] extends this
 * interface, so callers keep using the single `SourceRecordDao` type unchanged.
 */
interface SourceRecordResolutionDao {
    @Query("SELECT id FROM health_source_records WHERE sourceRecordId = :sourceRecordId")
    suspend fun getSourceRef(sourceRecordId: String): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: HealthSourceRecordEntity): Long

    @Query("SELECT * FROM health_source_records WHERE sourceRecordId IN (:sourceRecordIds)")
    suspend fun getSourcesByRecordIds(sourceRecordIds: List<String>): List<HealthSourceRecordEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoreAll(entities: List<HealthSourceRecordEntity>)
}

@Dao
interface SourceRecordDao : SourceRecordMaintenanceDao, SourceRecordResolutionDao {
    /**
     * WP-17/OD-1 delta-delete handling. `hr_minute_buckets` children cascade, but
     * `hr_source_minute_contributions` references this table with `ON DELETE RESTRICT`, so a
     * Health-Connect deletion of a source whose minutes were already rolled up would otherwise
     * raise `SQLiteConstraintException` and abort the whole ingestion transaction instead of
     * converging. Per Step 4, only that source's own contribution rows are removed -- no other
     * source's evidence is subtracted and no minute is blanket-deleted.
     *
     * The already-visible `hr_minute_buckets` projections and the `minute_coverage` rows of the
     * affected minutes are deliberately NOT regenerated here: a contribution carries no
     * `recordType`/`sessionId`, so one deleted contribution cannot be re-projected in isolation.
     * **T3 closes that gap in the reconcile pass instead of here.** `WarmTierRelinker` (run once
     * over the full range from `SessionLinkReconciler.reconcile`) rebuilds every affected minute
     * from whatever contributions *remain*, and retires a minute whose evidence is gone entirely
     * -- so a deletion converges on the next reconcile, which is exactly what the dirty work the
     * ingestion path appends schedules.
     */
    @Transaction
    suspend fun deleteBySourceRecordId(sourceRecordId: String): Int {
        deleteContributionsOfSourceRecordId(sourceRecordId)
        return deleteSourceRecordRow(sourceRecordId)
    }

    @Query(
        "DELETE FROM hr_source_minute_contributions WHERE sourceRecordRef IN (" +
            "SELECT id FROM health_source_records WHERE sourceRecordId = :sourceRecordId)",
    )
    suspend fun deleteContributionsOfSourceRecordId(sourceRecordId: String): Int

    @Query("DELETE FROM health_source_records WHERE sourceRecordId = :sourceRecordId")
    suspend fun deleteSourceRecordRow(sourceRecordId: String): Int

    @Query("SELECT * FROM health_source_records WHERE sourceRecordId = :sourceRecordId")
    suspend fun getBySourceRecordId(sourceRecordId: String): HealthSourceRecordEntity?

    @Query(
        "UPDATE health_source_records " +
            "SET originPackage = :originPackage, " +
            "recordStartMs = :recordStartMs, " +
            "recordEndExclusiveMs = :recordEndExclusiveMs, " +
            "lastModifiedMs = :lastModifiedMs, " +
            "metadataState = :metadataState, " +
            "sourceRevision = :sourceRevision " +
            "WHERE id = :id",
    )
    suspend fun updateAuthoritativeMetadata(
        id: Long,
        originPackage: String?,
        recordStartMs: Long,
        recordEndExclusiveMs: Long,
        lastModifiedMs: Long?,
        metadataState: String,
        sourceRevision: Long,
    ): Int

    @Query(
        "SELECT * FROM health_source_records " +
            "WHERE recordType = :recordType " +
            "AND metadataState = 'AUTHORITATIVE' " +
            "AND recordStartMs < :windowEndMs AND recordEndExclusiveMs > :windowStartMs " +
            "ORDER BY recordStartMs ASC",
    )
    suspend fun getAuthoritativeSourcesOverlapping(
        recordType: String,
        windowStartMs: Long,
        windowEndMs: Long,
    ): List<HealthSourceRecordEntity>

    @Query(
        "SELECT * FROM health_source_records " +
            "WHERE recordType = :recordType AND metadataState = 'AUTHORITATIVE' " +
            "AND recordStartMs < :windowEndMs AND recordEndExclusiveMs > :windowStartMs " +
            "AND id > :afterRef " +
            "AND sourceRecordId NOT IN (" +
            "  SELECT sourceId FROM scan_seen_ids " +
            "  WHERE runId = :runId AND chunkId = :chunkId AND recordType = :recordType) " +
            "ORDER BY id ASC LIMIT :limit",
    )
    suspend fun pageUnstagedAuthoritativeSources(
        recordType: String,
        windowStartMs: Long,
        windowEndMs: Long,
        runId: String,
        chunkId: String,
        afterRef: Long,
        limit: Int,
    ): List<HealthSourceRecordEntity>

    // PERF-003: candidate orphan metadata. A row qualifies only when nothing references it any
    // more: no raw HR/HRV children, no warm contribution evidence (OD-1 lineage), and no
    // in-flight staging naming it. Backup pages every remaining source, so this predicate is also
    // what keeps an export FK-complete.
    @Query(
        "SELECT id FROM health_source_records " +
            "WHERE id > :afterRef " +
            "AND NOT EXISTS (SELECT 1 FROM heart_rate_records WHERE sourceRecordRef = health_source_records.id) " +
            "AND NOT EXISTS (SELECT 1 FROM hrv_records WHERE sourceRecordRef = health_source_records.id) " +
            "AND NOT EXISTS (" +
            "  SELECT 1 FROM hr_source_minute_contributions " +
            "  WHERE sourceRecordRef = health_source_records.id) " +
            "AND NOT EXISTS (" +
            "  SELECT 1 FROM staged_hr_sources WHERE sourceId = health_source_records.sourceRecordId) " +
            "AND NOT EXISTS (" +
            "  SELECT 1 FROM scan_seen_ids WHERE sourceId = health_source_records.sourceRecordId) " +
            "ORDER BY id ASC LIMIT :limit",
    )
    suspend fun pageUnreferencedSourceIds(afterRef: Long, limit: Int): List<Long>

    @Query("DELETE FROM health_source_records WHERE id IN (:ids)")
    suspend fun deleteSourcesByRefs(ids: List<Long>): Int
}

suspend fun SourceRecordDao.getOrCreateSourceRef(
    sourceRecordId: String,
    recordType: String,
    createdAtMs: Long,
): Long {
    val existing = getSourceRef(sourceRecordId)
    if (existing != null) return existing
    insertIgnore(
        HealthSourceRecordEntity(
            sourceRecordId = sourceRecordId,
            recordType = recordType,
            createdAtMs = createdAtMs,
        ),
    )
    return getSourceRef(sourceRecordId) ?: error("Failed to create source ref for $sourceRecordId")
}

suspend fun SourceRecordDao.upsertIntervalSourceRecord(
    sourceRecordId: String,
    recordType: String,
    startMs: Long,
    endExclusiveMs: Long,
    originPackage: String?,
    lastModifiedMs: Long?,
) {
    val existing = getBySourceRecordId(sourceRecordId)
    if (existing != null) {
        updateAuthoritativeMetadata(
            id = existing.id,
            originPackage = originPackage,
            recordStartMs = startMs,
            recordEndExclusiveMs = endExclusiveMs,
            lastModifiedMs = lastModifiedMs,
            metadataState = "AUTHORITATIVE",
            sourceRevision = existing.sourceRevision + 1L,
        )
    } else {
        insertIgnore(
            HealthSourceRecordEntity(
                sourceRecordId = sourceRecordId,
                recordType = recordType,
                createdAtMs = startMs,
                originPackage = originPackage,
                recordStartMs = startMs,
                recordEndExclusiveMs = endExclusiveMs,
                lastModifiedMs = lastModifiedMs,
                metadataState = "AUTHORITATIVE",
                sourceRevision = 0L,
            ),
        )
    }
}
