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

@Dao
interface SourceRecordDao : SourceRecordMaintenanceDao {
    @Query("SELECT id FROM health_source_records WHERE sourceRecordId = :sourceRecordId")
    suspend fun getSourceRef(sourceRecordId: String): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: HealthSourceRecordEntity): Long

    @Query("DELETE FROM health_source_records WHERE sourceRecordId = :sourceRecordId")
    suspend fun deleteBySourceRecordId(sourceRecordId: String): Int

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



