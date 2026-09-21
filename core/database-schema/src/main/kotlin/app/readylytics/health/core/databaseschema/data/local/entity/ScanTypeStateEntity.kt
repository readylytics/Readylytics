package app.readylytics.health.core.databaseschema.data.local.entity

import androidx.room.Entity

/**
 * WP-18/HC-002 scan completeness. Deletion reconciliation may only run for a key whose state is
 * `COMPLETE`: a resumed or cancelled scan leaves `SCANNING`, and unseen pages must never be
 * interpreted as deletions.
 */
@Entity(tableName = "scan_type_state", primaryKeys = ["runId", "chunkId", "recordType"])
data class ScanTypeStateEntity(
    val runId: String,
    val chunkId: String,
    val recordType: String,
    val state: String,
    val stagedCount: Int,
    val updatedAtMs: Long,
)
