package app.readylytics.health.data.local.entity

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "hrv_records",
    indices = [
        Index(value = ["sourceRecordId", "timestampMs"], unique = true),
        Index(value = ["timestampMs"]),
        Index(value = ["recordType", "timestampMs"]),
        Index(value = ["sessionId"]),
    ],
)
data class HrvRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val rowId: Long = 0,
    val sourceRecordId: String,
    val timestampMs: Long,
    val rmssdMs: Float,
    val recordType: String,
    val sessionId: String? = null,
    val deviceName: String? = null,
) {
    val id: String get() = sourceRecordId

    @Ignore
    constructor(
        id: String,
        timestampMs: Long,
        rmssdMs: Float,
        recordType: String,
        sessionId: String? = null,
        deviceName: String? = null,
    ) : this(
        rowId = 0L,
        sourceRecordId = id,
        timestampMs = timestampMs,
        rmssdMs = rmssdMs,
        recordType = recordType,
        sessionId = sessionId,
        deviceName = deviceName,
    )
}
