package app.readylytics.health.core.model.domain.sync

import app.readylytics.health.core.model.domain.model.HealthDataType

/**
 * WP-18: identity of one scan unit. `runId` is the immutable historical run id
 * ([HistoricalRunIdentity.runId]) or the fixed daily-sync id; `chunkId` identifies the window
 * inside that run (epoch day of the chunk start for a resync, window start millis for a daily
 * sync). Staged identities are scoped by this pair so two runs can never read each other's scan.
 */
data class ScanIdentity(
    val runId: String,
    val chunkId: String,
)

enum class TypeScanState {
    SCANNING,
    COMPLETE,
}

/**
 * Durable replacement for the per-chunk heap `Set<String>` of scanned Health Connect record ids
 * (PERF-001), and the record of whether that scan was complete (HC-002). Deletion reconciliation
 * anti-joins against this staging and refuses to run for a key that is not [TypeScanState.COMPLETE]
 * — unseen pages must never be interpreted as deletions.
 */
interface ScanStagingStore {
    /**
     * Opens (or reopens) the scan of [type] for [scan]. With `resume = false` any previously staged
     * identities for exactly that `(runId, chunkId, type)` are dropped first, so a restarted scan
     * cannot inherit a half-populated set. With `resume = true` they are kept and extended.
     */
    suspend fun beginTypeScan(
        scan: ScanIdentity,
        type: HealthDataType,
        resume: Boolean,
    )

    /** Stages [ids] in bounded batches. Idempotent: re-staging the same id changes nothing. */
    suspend fun stageIds(
        scan: ScanIdentity,
        type: HealthDataType,
        ids: Collection<String>,
    )

    suspend fun markTypeScanComplete(
        scan: ScanIdentity,
        type: HealthDataType,
    )

    suspend fun stateOf(
        scan: ScanIdentity,
        type: HealthDataType,
    ): TypeScanState?

    suspend fun stagedCount(
        scan: ScanIdentity,
        type: HealthDataType,
    ): Int

    suspend fun clearTypeScan(
        scan: ScanIdentity,
        type: HealthDataType,
    )

    suspend fun clearRun(runId: String)

    /** Purges abandoned generations: every staged row whose `runId` is not [runId]. */
    suspend fun clearRunsOtherThan(runId: String)

    companion object {
        /** Bounded insert batch; stays far below the 999-variable floor at 4 columns per row. */
        const val STAGE_BATCH_SIZE: Int = 500
    }
}

class InMemoryScanStagingStore : ScanStagingStore {
    private val staged = mutableMapOf<Triple<String, String, String>, MutableSet<String>>()
    private val states = mutableMapOf<Triple<String, String, String>, TypeScanState>()

    fun stagedIds(scan: ScanIdentity, type: HealthDataType): Set<String> =
        staged[Triple(scan.runId, scan.chunkId, type.name)]?.toSet() ?: emptySet()

    fun clearAll() {
        staged.clear()
        states.clear()
    }

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
}
