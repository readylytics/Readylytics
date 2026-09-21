package app.readylytics.health.core.model.domain.sync

import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.repository.ReadOutcome

/**
 * WP-18: one completed type scan of one window. The scanned record identities live in
 * [ScanStagingStore] under [scan], not on the heap — deletion reconciliation anti-joins against
 * `scan_seen_ids` and refuses to run unless that scan is [TypeScanState.COMPLETE] (HC-002).
 */
data class CompleteTypeScan(
    val type: HealthDataType,
    val windowStartMs: Long,
    val windowEndExclusiveMs: Long,
    val sourceSelectionId: String,
    val scan: ScanIdentity,
    @Deprecated("Task 3 bridge until Task 4 stages scan identities via ScanStagingStore")
    val ids: Set<String> = emptySet(),
)

fun ReadOutcome<Unit>.isComplete(): Boolean = this is ReadOutcome.Available
