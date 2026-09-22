package app.readylytics.health.core.databaseschema.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "heart_rate_records",
    foreignKeys = [
        ForeignKey(
            entity = HealthSourceRecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceRecordRef"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(name = "index_hr_v10_source_time", value = ["sourceRecordRef", "timestampMs"], unique = true),
        Index(name = "index_hr_v10_timestamp", value = ["timestampMs"]),
        Index(name = "index_hr_v10_session_type_bpm", value = ["sessionId", "recordType", "beatsPerMinute"]),
        Index(name = "index_hr_v10_type_timestamp", value = ["recordType", "timestampMs"]),
        // Phase 2 Task 10: proven by EXPLAIN QUERY PLAN, not speculative. Without this,
        // HeartRateDao.pagePlausibleSamplesForRollup's `ORDER BY timestampMs ASC, sourceRecordRef
        // ASC` could only use the single-column index_hr_v10_timestamp for the range filter and the
        // *left* part of the sort; SQLite still needed `USE TEMP B-TREE FOR RIGHT PART OF ORDER BY`
        // to break timestampMs ties, forcing the rollup streamer's keyset page to materialize and
        // sort every matching row instead of walking an index directly in keyset order.
        Index(name = "index_hr_v10_timestamp_source", value = ["timestampMs", "sourceRecordRef"]),
    ],
)
data class HeartRateRecordEntity(
    /**
     * Integer FK to [HealthSourceRecordEntity.id], normalizing the per-row source UUID out of the
     * hot tier. Idempotent re-ingestion upserts on the unique (sourceRecordRef, timestampMs) index,
     * so the same source record updates mutable columns in place and preserves rowId — never persist
     * or compare rowId across backup/restore (restore deleteAll's then reinserts, so rowIds renumber).
     */
    val sourceRecordRef: Long,
    val timestampMs: Long,
    val beatsPerMinute: Int,
    val recordType: String,
    val sessionId: String? = null,
    val deviceName: String? = null,
    @PrimaryKey(autoGenerate = true)
    val rowId: Long = 0,
)
