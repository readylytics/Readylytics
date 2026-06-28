package app.readylytics.health.domain.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

data class RecalcProgress(
    val current: Int,
    val total: Int,
)

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
