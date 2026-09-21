package app.readylytics.health.core.databaseschema.data.local.entity

import androidx.room.Entity

@Entity(tableName = "staged_hr_sources", primaryKeys = ["runId", "sourceId"])
data class StagedSourceMetadataEntity(
    val runId: String,
    val sourceId: String,
    val recordType: String,
    val originPackage: String?,
    val startMs: Long,
    val endExclusiveMs: Long,
    val lastModifiedMs: Long?,
    val payloadComplete: Boolean,
)
