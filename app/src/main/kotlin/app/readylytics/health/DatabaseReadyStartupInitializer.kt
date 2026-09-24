package app.readylytics.health

import android.content.Context
import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import app.readylytics.health.core.model.domain.migration.DatabaseReadiness
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.WorkoutTrimpBackfillStatus
import app.readylytics.health.core.model.domain.sync.DirtyRangeStore
import app.readylytics.health.core.model.domain.sync.DirtyTicket
import app.readylytics.health.core.model.domain.sync.HealthMutationCoordinator
import app.readylytics.health.core.model.domain.sync.RecalcTrigger
import app.readylytics.health.core.model.domain.sync.ScoringRunContext
import app.readylytics.health.core.model.domain.util.logD
import app.readylytics.health.core.model.domain.util.logE
import app.readylytics.health.core.model.domain.util.logI
import app.readylytics.health.core.model.workers.WorkerScheduler
import app.readylytics.health.core.scoring.domain.scoring.BackfillHistoricalBaselinesUseCase
import app.readylytics.health.crashreport.CachePrune
import app.readylytics.health.data.backup.RestoreMaintenanceCoordinator
import app.readylytics.health.data.preferences.PhysiologyPreferences
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.domain.migration.DatabaseMigrationUiState
import dagger.Lazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean

internal class DatabaseReadyStartupInitializer(
    private val backfillHistoricalBaselines: Lazy<BackfillHistoricalBaselinesUseCase>,
    private val settingsRepository: Lazy<SettingsRepository>,
    private val physiologyPreferences: Lazy<PhysiologyPreferences>,
    private val workerScheduler: WorkerScheduler,
    private val workoutTrimpBackfillStatus: Lazy<WorkoutTrimpBackfillStatus>,
    private val healthMutationCoordinator: Lazy<HealthMutationCoordinator>,
    private val context: Context? = null,
    private val dirtyRangeStore: Lazy<DirtyRangeStore>? = null,
    private val restoreMaintenanceCoordinator: Lazy<RestoreMaintenanceCoordinator>? = null,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val initialized = AtomicBoolean(false)

    suspend fun initializeIfReady(readiness: DatabaseReadiness): StartupInitializationResult {
        if (readiness != DatabaseReadiness.Ready) return StartupInitializationResult.NOT_READY
        return if (initialized.compareAndSet(false, true)) {
            initializeDatabase()
        } else {
            StartupInitializationResult.COMPLETE
        }
    }

    private suspend fun initializeDatabase(): StartupInitializationResult {
        return try {
            if (context != null) {
                runNonFatal("Orphan backup staging cleanup") {
                    CachePrune.pruneBackupStaging(context)
                }
            }

            if (restoreMaintenanceCoordinator != null) {
                val recoveryCoordinator = restoreMaintenanceCoordinator.get()
                runNonFatal("Restore recovery") {
                    recoveryCoordinator.recoverInterruptedRestoreOnStartup()
                }
                if (recoveryCoordinator.isMaintenancePending()) {
                    initialized.set(false)
                    return StartupInitializationResult.RETRYABLE_FAILURE
                }
            }

            runNonFatal("Historical baseline backfill") {
                val backfilled =
                    healthMutationCoordinator.get().withMutation {
                        backfillHistoricalBaselines.get().execute()
                    }
                if (backfilled > 0) {
                    logD(TAG) { "Backfilled $backfilled historical baselines" }
                }
            }

            val migrationCompleted =
                runNonFatal("TRIMP normalization migration") {
                    physiologyPreferences.get().migrateTrimpDefaultsIfNeeded()
                }

            val settings = settingsRepository.get()
            if (migrationCompleted) {
                runNonFatal("Recompute-only resync check") {
                    scheduleRecomputeResyncIfNeeded(settings.userPreferences.first())
                }
            }

            scheduleStartupWorkers(settings)
            StartupInitializationResult.COMPLETE
        } catch (e: CancellationException) {
            initialized.set(false)
            throw e
        } catch (e: Exception) {
            initialized.set(false)
            logE(TAG, e) { "Database-ready startup initialization failed" }
            StartupInitializationResult.RETRYABLE_FAILURE
        }
    }

    private suspend fun scheduleStartupWorkers(settings: SettingsRepository) {
        val backupSchedule = settings.backupSchedule.first()
        val backgroundSyncEnabled = settings.backgroundSyncEnabled.first()
        val periodicSyncMinutes =
            if (backgroundSyncEnabled) {
                settings.backgroundSyncIntervalMinutes.first()
            } else {
                null
            }
        workerScheduler.scheduleBackupWorker(backupSchedule)
        workerScheduler.scheduleBirthdayWorker()
        workerScheduler.scheduleDataCleanupWorker()
        workerScheduler.scheduleDataRollupWorker()
        if (periodicSyncMinutes != null) {
            workerScheduler.schedulePeriodicSync(periodicSyncMinutes.toLong())
        } else {
            workerScheduler.cancelPeriodicSync()
        }
    }

    /**
     * Two gates share one enqueue: a stale scoring version, and retained workouts whose canonical
     * `modelTrimp` was never backfilled (HIGH-2). Both are healed by the same recompute-only
     * resync, so they are evaluated together and enqueued at most once per launch.
     *
     * Task 5: version 5 marks that a full retained-history recompute has run, which -- since
     * Task 4 wired `MorningRecommendationAssembler` into every `computeDailySummary` call -- now
     * also backfills `workoutRecommendationJson` for every retained day. A user stored at any
     * version below 5 (including 4) needs exactly this same enqueue; only
     * [HealthResyncWorker.persistPostRecomputeState] is allowed to advance the stored version, and
     * only once its recompute range provably covered full retained history (see
     * [HealthResyncWorker.coversRetainedHistory]) -- never here, and never for a bounded pass.
     */
    private suspend fun scheduleRecomputeResyncIfNeeded(prefs: UserPreferences) {
        val storedScoringVersion = prefs.scoringVersion
        val needsVersionRecompute = storedScoringVersion < SettingsDefaults.CURRENT_SCORING_VERSION
        val runContext = ScoringRunContext.capture(prefs, clock.instant())
        val retentionStartMs = runContext.retentionStartMs
        val needsBackfillRecompute =
            workoutTrimpBackfillStatus.get().hasUnbackfilledWorkouts(retentionStartMs)
        val pendingDirty = pendingDirtyTickets()
        if (!needsVersionRecompute && !needsBackfillRecompute && pendingDirty.isEmpty()) return

        val detail =
            "staleVersion=$needsVersionRecompute " +
                "stored=$storedScoringVersion current=${SettingsDefaults.CURRENT_SCORING_VERSION}, " +
                "unbackfilledCanonicalTrimp=$needsBackfillRecompute, " +
                "pendingDirty=${pendingDirty.joinToString(prefix = "[", postfix = "]") { it.describe() }}"
        logI(TAG) { "Enqueueing recompute-only resync ($detail)" }
        val trigger =
            when {
                needsVersionRecompute -> RecalcTrigger.STARTUP_SCORING_VERSION
                needsBackfillRecompute -> RecalcTrigger.STARTUP_TRIMP_BACKFILL
                else -> RecalcTrigger.STARTUP_PENDING_DIRTY
            }
        // The worker owns the version bump (HealthResyncWorker.persistPostRecomputeState, on
        // success only). Never bump here: a killed worker must leave the stale version in
        // place so the next launch re-enqueues idempotently. The backfill gate converges the same
        // way: a recompute writes modelTrimp for every workout it touches, so the count drops to
        // zero and the gate stops firing.
        workerScheduler.scheduleResyncWorker(recomputeOnly = true, trigger = trigger, triggerDetail = detail)
    }

    /**
     * Pending dirty work, after dropping tickets written by the retired rollup/retention journaling
     * (older builds): those would otherwise re-enqueue a multi-week recompute on every start. Any
     * failure reads as "nothing pending" so a locked database never blocks startup.
     */
    private suspend fun pendingDirtyTickets(): List<DirtyTicket> {
        val store = dirtyRangeStore?.get() ?: return emptyList()
        return try {
            val discarded = store.discardRetiredAgingTickets()
            if (discarded > 0) logI(TAG) { "Discarded $discarded retired aging dirty tickets" }
            store.pending(PENDING_TICKET_LIMIT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logE(TAG, e) { "Pending dirty range query failed" }
            emptyList()
        }
    }

    private fun DirtyTicket.describe(): String = "$reason:$nextDay..$endInclusive"

    private suspend fun runNonFatal(
        actionName: String,
        block: suspend () -> Unit,
    ): Boolean =
        try {
            block()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logE(TAG, e) { "$actionName failed" }
            false
        }

    private companion object {
        const val TAG = "HealthDashboardApplication"
        const val PENDING_TICKET_LIMIT = 100
    }
}

internal enum class StartupInitializationResult {
    COMPLETE,
    NOT_READY,
    RETRYABLE_FAILURE,
}

internal class DatabaseReadyStartupCoordinator(
    private val initializer: DatabaseReadyStartupInitializer,
    private val retryDelaysMillis: List<Long> = DEFAULT_RETRY_DELAYS_MILLIS,
    private val waitBeforeRetry: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun observe(states: StateFlow<DatabaseMigrationUiState>) {
        states.collectLatest { state ->
            initializeWithRetry(states, state.readiness)
        }
    }

    private suspend fun initializeWithRetry(
        states: StateFlow<DatabaseMigrationUiState>,
        readiness: DatabaseReadiness,
    ) {
        var result = initializer.initializeIfReady(readiness)
        val retryDelays = retryDelaysMillis.iterator()
        while (result == StartupInitializationResult.RETRYABLE_FAILURE && retryDelays.hasNext()) {
            waitBeforeRetry(retryDelays.next())
            if (states.value.readiness != DatabaseReadiness.Ready) break
            result = initializer.initializeIfReady(DatabaseReadiness.Ready)
        }
    }

    private companion object {
        val DEFAULT_RETRY_DELAYS_MILLIS = listOf(500L, 2_000L, 8_000L)
    }
}
