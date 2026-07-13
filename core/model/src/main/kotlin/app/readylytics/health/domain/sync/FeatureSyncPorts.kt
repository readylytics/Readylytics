package app.readylytics.health.domain.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

data class RecalcProgress(
    val phase: ResyncPhase,
    val current: Int,
    val total: Int,
)

/**
 * Overall fraction (0f-1f) across the whole resync. Each [ResyncPhase] owns an equal-width slice
 * (derived from [ResyncPhase.entries] so it self-adjusts if a phase is ever added or removed);
 * within its slice, a phase fills proportionally if it reports real [RecalcProgress.total]
 * (INGEST batches, RECOMPUTE days) or simply holds at the slice start if it doesn't (PRUNE, RECONCILE).
 */
fun RecalcProgress.fraction(): Float {
    val sliceSize = 1f / ResyncPhase.entries.size
    val sliceStart = phase.ordinal * sliceSize
    val withinSlice = if (total > 0) (current.toFloat() / total) * sliceSize else 0f
    return sliceStart + withinSlice
}

data class HistoricalResyncState(
    val running: Boolean,
    val current: Int,
    val total: Int,
)

interface ForegroundSyncGateway {
    val isSyncing: StateFlow<Boolean>
    val recalcProgress: StateFlow<RecalcProgress?>
    val syncCompletedEvent: SharedFlow<Unit>

    suspend fun evaluateAndSync()

    suspend fun triggerImmediateSync()

    suspend fun triggerDailySync()
}

interface HealthDataRefresh {
    suspend fun refreshAffectedWindow()
}

interface HistoricalResyncController {
    val state: Flow<HistoricalResyncState>

    suspend fun requestHistoricalResync()

    fun schedulePeriodicSync(intervalMinutes: Long)

    fun cancelPeriodicSync()
}
