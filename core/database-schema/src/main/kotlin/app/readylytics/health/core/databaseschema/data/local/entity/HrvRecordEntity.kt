package app.readylytics.health.core.databaseschema.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "hrv_records",
    foreignKeys = [
        ForeignKey(
            entity = HealthSourceRecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceRecordRef"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(name = "index_hrv_v10_source_time", value = ["sourceRecordRef", "timestampMs"], unique = true),
        Index(name = "index_hrv_v10_timestamp", value = ["timestampMs"]),
        Index(name = "index_hrv_v10_type_timestamp", value = ["recordType", "timestampMs"]),
        Index(name = "index_hrv_v10_session", value = ["sessionId"]),
    ],
)
data class HrvRecordEntity(
    /**
     * Integer FK to [HealthSourceRecordEntity.id], normalizing the per-row source UUID out of the
     * hot tier. Idempotent re-ingestion upserts on the unique (sourceRecordRef, timestampMs) index.
     */
    val sourceRecordRef: Long,
    val timestampMs: Long,
    val rmssdMs: Float,
    val recordType: String,
    val sessionId: String? = null,
    val deviceName: String? = null,
    @PrimaryKey(autoGenerate = true)
    val rowId: Long = 0,
)
