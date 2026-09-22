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
    indices = [
        Index(value = ["runId", "chunkId", "recordType"]),
        // Phase 2 Task 10: proven by EXPLAIN QUERY PLAN, not speculative. The composite PRIMARY KEY
        // (runId, chunkId, recordType, sourceId) and the index above both lead with runId, so
        // SourceRecordDao.pageUnreferencedSourceIds' `NOT EXISTS (... WHERE sourceId = ?)` GC check
        // -- which has no runId to bind -- fell back to a bare `SCAN TABLE scan_seen_ids` (not even
        // an index-covering scan) once per GC page candidate. A leading-sourceId index lets it SEARCH
        // instead.
        Index(value = ["sourceId"]),
    ],
)
data class ScanSeenIdEntity(
    val runId: String,
    val chunkId: String,
    val recordType: String,
    val sourceId: String,
)
