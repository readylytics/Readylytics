package app.readylytics.health.data.local.entity

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "heart_rate_records",
    indices = [
        Index(name = "index_hr_v7_source_time", value = ["sourceRecordId", "timestampMs"], unique = true),
        Index(name = "index_hr_v7_timestamp", value = ["timestampMs"]),
        Index(name = "index_hr_v7_session_type_bpm", value = ["sessionId", "recordType", "beatsPerMinute"]),
        Index(name = "index_hr_v7_type_timestamp", value = ["recordType", "timestampMs"]),
    ],
)
data class HeartRateRecordEntity(
    /**
     * Stable across idempotent re-ingestion: [HeartRateDao.upsertAll] uses a conflict-targeted
     * UPSERT on the unique (sourceRecordId, timestampMs) index, so re-upserting the same source
     * record updates mutable columns (recordType/sessionId/deviceName) in place and preserves
     * rowId — unlike the former `@Insert(onConflict = REPLACE)`, which deleted+reinserted and
     * rotated rowId on every re-ingest. Still never persist or compare rowId across backup/restore:
     * restore deleteAll's then reinserts, so rowIds are renumbered there.
     */
    @PrimaryKey(autoGenerate = true)
    val rowId: Long = 0,
    val sourceRecordId: String,
    val timestampMs: Long,
    val beatsPerMinute: Int,
    val recordType: String,
    val sessionId: String? = null,
    val deviceName: String? = null,
) {
    val id: String get() = sourceRecordId

    @Ignore
    constructor(
        id: String,
        timestampMs: Long,
        beatsPerMinute: Int,
        recordType: String,
        sessionId: String? = null,
        deviceName: String? = null,
    ) : this(
        rowId = 0L,
        sourceRecordId = id,
        timestampMs = timestampMs,
        beatsPerMinute = beatsPerMinute,
        recordType = recordType,
        sessionId = sessionId,
        deviceName = deviceName,
    )
}
