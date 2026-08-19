package app.readylytics.health

import app.readylytics.health.data.preferences.SettingsDefaults
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.domain.migration.DatabaseMigrationUiState
import app.readylytics.health.domain.migration.DatabaseReadiness
import app.readylytics.health.domain.scoring.BackfillHistoricalBaselinesUseCase
import app.readylytics.health.domain.sync.HealthSyncUseCase
import app.readylytics.health.domain.util.logD
import app.readylytics.health.domain.util.logE
import app.readylytics.health.workers.WorkerScheduler
import dagger.Lazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicBoolean

internal class DatabaseReadyStartupInitializer(
    private val healthSyncUseCase: Lazy<HealthSyncUseCase>,
    private val backfillHistoricalBaselines: Lazy<BackfillHistoricalBaselinesUseCase>,
    private val settingsRepository: Lazy<SettingsRepository>,
    private val workerScheduler: WorkerScheduler,
) {
    private val initialized = AtomicBoolean(false)

    suspend fun initializeIfReady(readiness: DatabaseReadiness): StartupInitializationResult {
        if (readiness != DatabaseReadiness.Ready) return StartupInitializationResult.NOT_READY
        if (!initialized.compareAndSet(false, true)) return StartupInitializationResult.COMPLETE

        return try {
            try {
                val backfilled =
                    healthSyncUseCase.get().withSyncLock {
                        backfillHistoricalBaselines.get().execute()
                    }
                if (backfilled > 0) {
                    logD(TAG) { "Backfilled $backfilled historical baselines" }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logE(TAG, e) { "Historical baseline backfill failed" }
            }

            val settings = settingsRepository.get()
            try {
                val storedScoringVersion = settings.userPreferences.first().scoringVersion
                if (storedScoringVersion < SettingsDefaults.CURRENT_SCORING_VERSION) {
                    logD(TAG) {
                        "Scoring version $storedScoringVersion < ${SettingsDefaults.CURRENT_SCORING_VERSION}; " +
                            "enqueueing recompute-only resync"
                    }
                    workerScheduler.scheduleResyncWorker(recomputeOnly = true)
                    settings.updateScoringVersion(SettingsDefaults.CURRENT_SCORING_VERSION)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logE(TAG, e) { "Scoring version migration check failed" }
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
