package app.readylytics.health.core.databaseschema.data.local.entity

import androidx.room.Entity

@Entity(tableName = "staged_hr_samples", primaryKeys = ["runId", "sourceId", "timestampMs"])
data class StagedHeartRateEntity(
    val runId: String,
    val sourceId: String,
    val timestampMs: Long,
    val beatsPerMinute: Int,
    val recordType: String,
    val sessionId: String?,
    val deviceName: String?,
)
