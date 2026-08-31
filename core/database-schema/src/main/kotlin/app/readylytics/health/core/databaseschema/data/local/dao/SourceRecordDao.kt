package app.readylytics.health.core.databaseschema.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.readylytics.health.core.databaseschema.data.local.entity.HealthSourceRecordEntity

@Dao
interface SourceRecordDao {
    @Query("SELECT id FROM health_source_records WHERE sourceRecordId = :sourceRecordId")
    suspend fun getSourceRef(sourceRecordId: String): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: HealthSourceRecordEntity): Long

    @Transaction
    suspend fun getOrCreateSourceRef(
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

    @Query("DELETE FROM health_source_records WHERE sourceRecordId = :sourceRecordId")
    suspend fun deleteBySourceRecordId(sourceRecordId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<HealthSourceRecordEntity>)

    @Query("SELECT * FROM health_source_records ORDER BY id ASC")
    suspend fun getAll(): List<HealthSourceRecordEntity>

    @Query("SELECT COUNT(*) FROM health_source_records")
    suspend fun count(): Int

    @Query("DELETE FROM health_source_records")
    suspend fun deleteAll(): Int

    @Query(
        "SELECT * FROM health_source_records " +
            "WHERE recordType = :recordType AND createdAtMs >= :startMs AND createdAtMs <= :endMs " +
            "ORDER BY createdAtMs ASC",
    )
    suspend fun getByRecordTypeAndRange(
        recordType: String,
        startMs: Long,
        endMs: Long,
    ): List<HealthSourceRecordEntity>
}
