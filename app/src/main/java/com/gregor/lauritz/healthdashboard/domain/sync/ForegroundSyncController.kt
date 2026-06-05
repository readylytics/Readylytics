package com.gregor.lauritz.healthdashboard.domain.sync

import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.preferences.SyncPreference
import com.gregor.lauritz.healthdashboard.domain.model.getOrThrow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ForegroundSyncController
    @Inject
    constructor(
        private val settingsRepo: SettingsRepository,
        private val syncUseCase: HealthSyncUseCase,
    ) {
        private val syncMutex = Mutex()

        private val _syncCompletedEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val syncCompletedEvent: SharedFlow<Unit> = _syncCompletedEvent.asSharedFlow()

        private val _isSyncing = MutableStateFlow(false)
        val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

        // Determinate recalculation progress ("day X of Y"); null when no walk-forward is running.
        private val _recalcProgress = MutableStateFlow<RecalcProgress?>(null)
        val recalcProgress: StateFlow<RecalcProgress?> = _recalcProgress.asStateFlow()

        suspend fun evaluateAndSync() {
            com.gregor.lauritz.healthdashboard.domain.util
                .logD("ForegroundSyncController") { "evaluateAndSync called" }
            val prefs = settingsRepo.userPreferences.first()
            when (prefs.syncPreference) {
                SyncPreference.NEVER -> {
                    com.gregor.lauritz.healthdashboard.domain.util.logD(
                        "ForegroundSyncController",
                    ) { "Sync disabled by user preference" }
                    return
                }
                SyncPreference.ALWAYS -> {
                    com.gregor.lauritz.healthdashboard.domain.util.logD(
                        "ForegroundSyncController",
                    ) { "Sync type: ALWAYS" }
                    executeSync(isFirstSync = prefs.lastSyncTimestamp == 0L)
                }
                SyncPreference.BY_TIME -> {
                    val intervalMs = prefs.syncIntervalHours * 3_600_000L
                    val timeSinceLast = System.currentTimeMillis() - prefs.lastSyncTimestamp
                    com.gregor.lauritz.healthdashboard.domain.util.logD("ForegroundSyncController") {
                        "Sync type: BY_TIME. Time since last: ${timeSinceLast / 1000}s, Interval: ${intervalMs / 1000}s"
                    }
                    if (timeSinceLast > intervalMs) {
                        executeSync(isFirstSync = prefs.lastSyncTimestamp == 0L)
                    } else {
                        com.gregor.lauritz.healthdashboard.domain.util.logD(
                            "ForegroundSyncController",
                        ) { "Sync skipped: interval not met" }
                    }
                }
            }
        }

        suspend fun triggerImmediateSync() {
            com.gregor.lauritz.healthdashboard.domain.util.logD(
                "ForegroundSyncController",
            ) { "triggerImmediateSync called" }
            executeSync(isFirstSync = true)
        }

        /**
         * Pull-to-refresh entry point: recalculates the current day only (fast, foreground). Full
         * historical recalculation lives behind the Settings "Resync Health Connect data" button,
         * which runs durably in WorkManager.
         */
        suspend fun triggerDailySync() {
            com.gregor.lauritz.healthdashboard.domain.util.logD(
                "ForegroundSyncController",
            ) { "triggerDailySync called (current day only)" }
            executeSync(isFirstSync = false, windowDays = 1)
        }

        // --- Background (WorkManager) recalculation publishing -------------------------------------
        // The historical resync runs in HealthResyncWorker. It publishes into the same StateFlows so
        // the existing dashboard progress banner and completion snackbar surface it with no UI rewrite.

        fun onBackgroundRecalcStarted() {
            _isSyncing.value = true
            _recalcProgress.value = null
        }

        fun onBackgroundRecalcProgress(
            current: Int,
            total: Int,
        ) {
            _recalcProgress.value = RecalcProgress(current = current, total = total)
        }

        fun onBackgroundRecalcFinished(success: Boolean) {
            _isSyncing.value = false
            _recalcProgress.value = null
            if (success) _syncCompletedEvent.tryEmit(Unit)
        }

        private suspend fun executeSync(
            isFirstSync: Boolean,
            windowDays: Int? = null,
        ) {
            if (!syncMutex.tryLock()) {
                com.gregor.lauritz.healthdashboard.domain.util.logD("ForegroundSyncController") {
                    "Sync already in progress, skipping redundant request"
                }
                return
            }
            val onProgress: (Int, Int) -> Unit = { current, total ->
                _recalcProgress.value = RecalcProgress(current = current, total = total)
            }
            try {
                _isSyncing.value = true
                if (isFirstSync) {
                    com.gregor.lauritz.healthdashboard.domain.util.logD(
                        "ForegroundSyncController",
                    ) { "Running catch-up sync..." }
                    syncUseCase.catchUpSync(onProgress).getOrThrow()
                } else if (windowDays != null) {
                    syncUseCase.sync(windowDays = windowDays, onProgress = onProgress).getOrThrow()
                } else {
                    syncUseCase.sync(onProgress = onProgress).getOrThrow()
                }
                com.gregor.lauritz.healthdashboard.domain.util
                    .logD("ForegroundSyncController") { "Sync success" }
                _syncCompletedEvent.emit(Unit)
            } catch (e: Exception) {
                com.gregor.lauritz.healthdashboard.domain.util
                    .logD("ForegroundSyncController") { "Sync failed: ${e.message}" }
            } finally {
                _isSyncing.value = false
                _recalcProgress.value = null
                syncMutex.unlock()
            }
        }
    }

/**
 * Determinate progress for a historical walk-forward recalculation.
 *
 * @param current number of days recomputed so far
 * @param total   total number of days in this recalculation pass
 */
data class RecalcProgress(
    val current: Int,
    val total: Int,
)
