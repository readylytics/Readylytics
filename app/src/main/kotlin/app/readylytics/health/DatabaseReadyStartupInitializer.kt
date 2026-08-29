package app.readylytics.health

import app.readylytics.health.core.healthconnect.domain.sync.HealthSyncUseCase
import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import app.readylytics.health.core.model.domain.migration.DatabaseReadiness
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.WorkoutTrimpBackfillStatus
import app.readylytics.health.core.model.domain.util.RetentionBounds
import app.readylytics.health.core.model.domain.util.logD
import app.readylytics.health.core.model.domain.util.logE
import app.readylytics.health.core.model.workers.WorkerScheduler
import app.readylytics.health.core.scoring.domain.scoring.BackfillHistoricalBaselinesUseCase
import app.readylytics.health.data.preferences.PhysiologyPreferences
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.domain.migration.DatabaseMigrationUiState
import dagger.Lazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

internal class DatabaseReadyStartupInitializer(
    private val healthSyncUseCase: Lazy<HealthSyncUseCase>,
    private val backfillHistoricalBaselines: Lazy<BackfillHistoricalBaselinesUseCase>,
    private val settingsRepository: Lazy<SettingsRepository>,
    private val physiologyPreferences: Lazy<PhysiologyPreferences>,
    private val workerScheduler: WorkerScheduler,
    private val workoutTrimpBackfillStatus: Lazy<WorkoutTrimpBackfillStatus>,
) {
    private val initialized = AtomicBoolean(false)

    suspend fun initializeIfReady(readiness: DatabaseReadiness): StartupInitializationResult {
        if (readiness != DatabaseReadiness.Ready) return StartupInitializationResult.NOT_READY
        if (!initialized.compareAndSet(false, true)) return StartupInitializationResult.COMPLETE

        return try {
            runNonFatal("Historical baseline backfill") {
                val backfilled =
                    healthSyncUseCase.get().withSyncLock {
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

    /**
     * Two gates share one enqueue: a stale scoring version, and retained workouts whose canonical
     * `modelTrimp` was never backfilled (HIGH-2). Both are healed by the same recompute-only
     * resync, so they are evaluated together and enqueued at most once per launch.
     */
    private suspend fun scheduleRecomputeResyncIfNeeded(prefs: UserPreferences) {
        val storedScoringVersion = prefs.scoringVersion
        val needsVersionRecompute = storedScoringVersion < SettingsDefaults.CURRENT_SCORING_VERSION
        val retentionStartMs =
            RetentionBounds
                .resolveResyncStartDate(prefs)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        val needsBackfillRecompute =
            workoutTrimpBackfillStatus.get().hasUnbackfilledWorkouts(retentionStartMs)
        if (!needsVersionRecompute && !needsBackfillRecompute) return

        logD(TAG) {
            "Enqueueing recompute-only resync (staleVersion=$needsVersionRecompute " +
                "stored=$storedScoringVersion current=${SettingsDefaults.CURRENT_SCORING_VERSION}, " +
                "unbackfilledCanonicalTrimp=$needsBackfillRecompute)"
        }
        // The worker owns the version bump (HealthResyncWorker.persistPostRecomputeState, on
        // success only). Never bump here: a killed worker must leave the stale version in
        // place so the next launch re-enqueues idempotently. The backfill gate converges the same
        // way: a recompute writes modelTrimp for every workout it touches, so the count drops to
        // zero and the gate stops firing.
        workerScheduler.scheduleResyncWorker(recomputeOnly = true)
    }

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
        if (result != StartupInitializationResult.RETRYABLE_FAILURE) return

        for (retryDelayMillis in retryDelaysMillis) {
            waitBeforeRetry(retryDelayMillis)
            if (states.value.readiness != DatabaseReadiness.Ready) return
            result = initializer.initializeIfReady(DatabaseReadiness.Ready)
            if (result != StartupInitializationResult.RETRYABLE_FAILURE) return
        }
    }

    private companion object {
        val DEFAULT_RETRY_DELAYS_MILLIS = listOf(500L, 2_000L, 8_000L)
    }
}
