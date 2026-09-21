package app.readylytics.health.core.databaseschema.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * WP-18/S3 scan staging: one Health Connect record identity observed by the scan of
 * `(runId, chunkId, recordType)`. Operational state only — never exported as health data, never
 * FK-joined to `health_source_records` (staging must not create live authoritative metadata just to
 * hold an id). Replaces the per-chunk heap `Set<String>` that grew with scanned cardinality
 * (PERF-001) and the `NOT IN (:validIds)` binding lists it fed (HC-002).
 */
@Entity(
    tableName = "scan_seen_ids",
    primaryKeys = ["runId", "chunkId", "recordType", "sourceId"],
    indices = [Index(value = ["runId", "chunkId", "recordType"])],
)
data class ScanSeenIdEntity(
    val runId: String,
    val chunkId: String,
    val recordType: String,
    val sourceId: String,
)
