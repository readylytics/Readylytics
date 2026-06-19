package app.readylytics.health.domain.sync

import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.preferences.SyncPreference
import app.readylytics.health.domain.model.getOrThrow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
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

        private val _syncCompletedEvent =
            MutableSharedFlow<Unit>(
                extraBufferCapacity = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        val syncCompletedEvent: SharedFlow<Unit> = _syncCompletedEvent.asSharedFlow()

        private val _isSyncing = MutableStateFlow(false)
        val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

        // Determinate recalculation progress ("day X of Y"); null when no walk-forward is running.
        private val _recalcProgress = MutableStateFlow<RecalcProgress?>(null)
        val recalcProgress: StateFlow<RecalcProgress?> = _recalcProgress.asStateFlow()

        suspend fun evaluateAndSync() {
            app.readylytics.health.domain.util
                .logD("ForegroundSyncController") { "evaluateAndSync called" }
            val prefs = settingsRepo.userPreferences.first()
            when (prefs.syncPreference) {
                SyncPreference.NEVER -> {
                    app.readylytics.health.domain.util.logD(
                        "ForegroundSyncController",
                    ) { "Sync disabled by user preference" }
                    return
                }
                SyncPreference.ALWAYS -> {
                    app.readylytics.health.domain.util.logD(
                        "ForegroundSyncController",
                    ) { "Sync type: ALWAYS" }
                    val startTimestamp =
                        if (prefs.lastSyncTimestamp ==
                            0L
                        ) {
                            prefs.installDate
                        } else {
                            prefs.lastSyncTimestamp
                        }
                    val isFirst = prefs.lastSyncTimestamp == 0L && startTimestamp == 0L
                    val windowDays = if (isFirst) null else computeWindowDays(startTimestamp)
                    executeSync(isFirstSync = isFirst, windowDays = windowDays)
                }
                SyncPreference.BY_TIME -> {
                    val intervalMs = prefs.syncIntervalHours * 3_600_000L
                    val timeSinceLast = System.currentTimeMillis() - prefs.lastSyncTimestamp
                    app.readylytics.health.domain.util.logD("ForegroundSyncController") {
                        "Sync type: BY_TIME. Time since last: ${timeSinceLast / 1000}s, Interval: ${intervalMs / 1000}s"
                    }
                    if (timeSinceLast > intervalMs) {
                        val startTimestamp =
                            if (prefs.lastSyncTimestamp ==
                                0L
                            ) {
                                prefs.installDate
                            } else {
                                prefs.lastSyncTimestamp
                            }
                        val isFirst = prefs.lastSyncTimestamp == 0L && startTimestamp == 0L
                        val windowDays = if (isFirst) null else computeWindowDays(startTimestamp)
                        executeSync(isFirstSync = isFirst, windowDays = windowDays)
                    } else {
                        app.readylytics.health.domain.util.logD(
                            "ForegroundSyncController",
                        ) { "Sync skipped: interval not met" }
                    }
                }
            }
        }

        suspend fun triggerImmediateSync() {
            app.readylytics.health.domain.util.logD(
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
            app.readylytics.health.domain.util.logD(
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

        private fun computeWindowDays(lastSyncTimestamp: Long): Int {
            val lastSyncDate =
                Instant
                    .ofEpochMilli(lastSyncTimestamp)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
            val today = LocalDate.now(ZoneId.systemDefault())
            val daysSince = ChronoUnit.DAYS.between(lastSyncDate, today).toInt()
            return if (daysSince == 0) 1 else daysSince + 1
        }

        private suspend fun executeSync(
            isFirstSync: Boolean,
            windowDays: Int? = null,
        ) {
            if (!syncMutex.tryLock()) {
                app.readylytics.health.domain.util.logD("ForegroundSyncController") {
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
                    app.readylytics.health.domain.util.logD(
                        "ForegroundSyncController",
                    ) { "Running catch-up sync..." }
                    syncUseCase.catchUpSync(onProgress).getOrThrow()
                } else if (windowDays != null) {
                    syncUseCase.sync(windowDays = windowDays, onProgress = onProgress).getOrThrow()
                } else {
                    syncUseCase.sync(onProgress = onProgress).getOrThrow()
                }
                app.readylytics.health.domain.util
                    .logD("ForegroundSyncController") { "Sync success" }
                _syncCompletedEvent.emit(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                app.readylytics.health.domain.util
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
