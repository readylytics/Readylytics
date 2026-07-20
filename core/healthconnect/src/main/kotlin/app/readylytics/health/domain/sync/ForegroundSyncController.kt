package app.readylytics.health.domain.sync

import app.readylytics.health.domain.model.getOrThrow
import app.readylytics.health.domain.preferences.SettingsRepository
import app.readylytics.health.domain.preferences.SyncPreference
import app.readylytics.health.domain.preferences.UserPreferences
import app.readylytics.health.domain.preferences.scoringZone
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
        private val workerScheduler: dagger.Lazy<app.readylytics.health.workers.WorkerScheduler>,
    ) : ForegroundSyncGateway {
        private val syncMutex = Mutex()

        private val _syncCompletedEvent =
            MutableSharedFlow<Unit>(
                extraBufferCapacity = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        override val syncCompletedEvent: SharedFlow<Unit> = _syncCompletedEvent.asSharedFlow()

        private val _isSyncing = MutableStateFlow(false)
        override val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

        // Phase-tagged resync progress (INGEST/PRUNE/RECONCILE/RECOMPUTE); null when no resync is running.
        private val _recalcProgress = MutableStateFlow<RecalcProgress?>(null)
        override val recalcProgress: StateFlow<RecalcProgress?> = _recalcProgress.asStateFlow()

        override suspend fun evaluateAndSync() {
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
                    runCappedCatchUpSync(prefs)
                }
                SyncPreference.BY_TIME -> {
                    val intervalMs = prefs.syncIntervalHours * 3_600_000L
                    val timeSinceLast = System.currentTimeMillis() - prefs.lastSyncTimestamp
                    app.readylytics.health.domain.util.logD("ForegroundSyncController") {
                        "Sync type: BY_TIME. Time since last: ${timeSinceLast / 1000}s, Interval: ${intervalMs / 1000}s"
                    }
                    if (timeSinceLast > intervalMs) {
                        runCappedCatchUpSync(prefs)
                    } else {
                        app.readylytics.health.domain.util.logD(
                            "ForegroundSyncController",
                        ) { "Sync skipped: interval not met" }
                    }
                }
            }
        }

        /**
         * Runs the app-open catch-up sync, capped at [MAX_INLINE_RECOMPUTE_DAYS] (HC-007): a
         * foreground, UI-blocking sync must never silently widen to an unbounded window just
         * because the app was closed for a long time -- that recreates the "Pull-to-refresh = FULL
         * catch-up" problem the two-flow contract forbids through the front door. When the real
         * gap exceeds the cap, the capped inline sync still runs (so the user sees *something*
         * immediately) and the durable resync worker is enqueued for the remainder.
         */
        private suspend fun runCappedCatchUpSync(prefs: UserPreferences) {
            val isFirst = prefs.lastSyncTimestamp == 0L
            if (isFirst) {
                executeSync(isFirstSync = true, windowDays = null)
                return
            }
            val startTimestamp = prefs.lastSyncTimestamp
            val zoneId = prefs.scoringZone()
            val uncappedWindowDays = computeWindowDays(startTimestamp, zoneId)
            val windowDays = uncappedWindowDays.coerceAtMost(MAX_INLINE_RECOMPUTE_DAYS)
            executeSync(isFirstSync = false, windowDays = windowDays)
            if (uncappedWindowDays > MAX_INLINE_RECOMPUTE_DAYS) {
                app.readylytics.health.domain.util.logD("ForegroundSyncController") {
                    "Catch-up window ($uncappedWindowDays days) exceeds the inline cap " +
                        "($MAX_INLINE_RECOMPUTE_DAYS); ran the capped window and enqueued the resync worker"
                }
                workerScheduler.get().scheduleResyncWorker()
            }
        }

        override suspend fun triggerImmediateSync() {
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
        override suspend fun triggerDailySync() {
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
            phase: ResyncPhase,
            current: Int,
            total: Int,
        ) {
            _recalcProgress.value = RecalcProgress(phase = phase, current = current, total = total)
        }

        fun onBackgroundRecalcFinished(success: Boolean) {
            _isSyncing.value = false
            _recalcProgress.value = null
            if (success) _syncCompletedEvent.tryEmit(Unit)
        }

        private fun computeWindowDays(
            lastSyncTimestamp: Long,
            zoneId: ZoneId,
        ): Int {
            val lastSyncDate = Instant.ofEpochMilli(lastSyncTimestamp).atZone(zoneId).toLocalDate()
            val today = LocalDate.now(zoneId)
            val daysSince = ChronoUnit.DAYS.between(lastSyncDate, today).toInt()
            return (daysSince + 1).coerceAtLeast(1)
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
            val onProgress: (ResyncPhase, Int, Int) -> Unit = { phase, current, total ->
                _recalcProgress.value = RecalcProgress(phase = phase, current = current, total = total)
            }
            try {
                _isSyncing.value = true
                val result =
                    if (isFirstSync) {
                        app.readylytics.health.domain.util.logD(
                            "ForegroundSyncController",
                        ) { "Running catch-up sync..." }
                        syncUseCase.catchUpSync(onProgress)
                    } else {
                        // Every non-first-sync call site (runCappedCatchUpSync, triggerDailySync)
                        // always supplies windowDays -- HC-009's dead fallback branch removed.
                        val requiredWindowDays =
                            requireNotNull(windowDays) { "windowDays is required when isFirstSync is false" }
                        syncUseCase.sync(windowDays = requiredWindowDays, onProgress = onProgress)
                    }

                if (result is app.readylytics.health.domain.model.Result.Failure &&
                    result.code == "REQUIRES_HISTORICAL_RESYNC"
                ) {
                    app.readylytics.health.domain.util.logD("ForegroundSyncController") {
                        "Sync requires historical resync, enqueuing worker"
                    }
                    workerScheduler.get().scheduleResyncWorker()
                } else {
                    result.getOrThrow()
                    app.readylytics.health.domain.util
                        .logD("ForegroundSyncController") { "Sync success" }
                    _syncCompletedEvent.emit(Unit)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                app.readylytics.health.domain.util
                    .logE("ForegroundSyncController", e) { "Sync failed" }
                throw e
            } finally {
                _isSyncing.value = false
                _recalcProgress.value = null
                syncMutex.unlock()
            }
        }
    }

