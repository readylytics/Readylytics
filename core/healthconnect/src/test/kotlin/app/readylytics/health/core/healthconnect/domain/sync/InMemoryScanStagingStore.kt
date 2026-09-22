package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.sync.ScanIdentity
import app.readylytics.health.core.model.domain.sync.ScanStagingStore
import app.readylytics.health.core.model.domain.sync.TypeScanState

/**
 * Heap-backed [ScanStagingStore] double for these tests only.
 *
 * It deliberately lives in a test source set: staging on the JVM heap is precisely the PERF-001
 * pattern WP-18 removed, and `StagedDeletionReconciler` anti-joins against the real
 * `scan_type_state`/`scan_seen_ids` tables, so a heap store can never let a scan reach
 * `COMPLETE` where the reconciler can see it. Production always binds `RoomScanStagingStore`
 * through `DatabaseRepositoryModule`; keeping this class out of `src/main` (and off any
 * constructor default) means losing that binding fails the build instead of silently degrading
 * deletion reconciliation at runtime.
 */
class InMemoryScanStagingStore : ScanStagingStore {
    internal val staged = mutableMapOf<Triple<String, String, String>, MutableSet<String>>()
    internal val states = mutableMapOf<Triple<String, String, String>, TypeScanState>()

    override suspend fun beginTypeScan(
        scan: ScanIdentity,
        type: HealthDataType,
        resume: Boolean,
    ) {
        val key = Triple(scan.runId, scan.chunkId, type.name)
        if (!resume) {
            staged[key]?.clear()
        }
        states[key] = TypeScanState.SCANNING
    }

    override suspend fun stageIds(
        scan: ScanIdentity,
        type: HealthDataType,
        ids: Collection<String>,
    ) {
        val key = Triple(scan.runId, scan.chunkId, type.name)
        staged.getOrPut(key) { mutableSetOf() }.addAll(ids)
    }

    override suspend fun markTypeScanComplete(
        scan: ScanIdentity,
        type: HealthDataType,
    ) {
        val key = Triple(scan.runId, scan.chunkId, type.name)
        states[key] = TypeScanState.COMPLETE
    }

    override suspend fun stateOf(
        scan: ScanIdentity,
        type: HealthDataType,
    ): TypeScanState? = states[Triple(scan.runId, scan.chunkId, type.name)]

    override suspend fun stagedCount(
        scan: ScanIdentity,
        type: HealthDataType,
    ): Int = staged[Triple(scan.runId, scan.chunkId, type.name)]?.size ?: 0

    override suspend fun clearTypeScan(
        scan: ScanIdentity,
        type: HealthDataType,
    ) {
        val key = Triple(scan.runId, scan.chunkId, type.name)
        staged.remove(key)
        states.remove(key)
    }

    override suspend fun clearRun(runId: String) {
        staged.keys.filter { it.first == runId }.forEach { staged.remove(it) }
        states.keys.filter { it.first == runId }.forEach { states.remove(it) }
    }

    override suspend fun clearRunsOtherThan(runId: String) {
        staged.keys.filter { it.first != runId }.forEach { staged.remove(it) }
        states.keys.filter { it.first != runId }.forEach { states.remove(it) }
    }

    override suspend fun clearChunksOtherThan(
        runId: String,
        keepChunkIds: Set<String>,
    ) {
        staged.keys.filter { it.first == runId && it.second !in keepChunkIds }.forEach { staged.remove(it) }
        states.keys.filter { it.first == runId && it.second !in keepChunkIds }.forEach { states.remove(it) }
    }
}

/**
 * Convenience accessors for [InMemoryScanStagingStore], kept as extension functions so the store's
 * member-function count stays well under detekt's `TooManyFunctions` threshold as
 * [ScanStagingStore] grows new operations.
 */
fun InMemoryScanStagingStore.stagedIds(
    scan: ScanIdentity,
    type: HealthDataType,
): Set<String> = staged[Triple(scan.runId, scan.chunkId, type.name)]?.toSet() ?: emptySet()

fun InMemoryScanStagingStore.clearAll() {
    staged.clear()
    states.clear()
}
