package app.readylytics.health

import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.domain.migration.DatabaseReadiness
import app.readylytics.health.domain.scoring.BackfillHistoricalBaselinesUseCase
import app.readylytics.health.domain.sync.HealthSyncUseCase
import app.readylytics.health.domain.util.logD
import app.readylytics.health.domain.util.logE
import app.readylytics.health.workers.WorkerScheduler
import dagger.Lazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicBoolean

internal class DatabaseReadyStartupInitializer(
    private val healthSyncUseCase: Lazy<HealthSyncUseCase>,
    private val backfillHistoricalBaselines: Lazy<BackfillHistoricalBaselinesUseCase>,
    private val settingsRepository: SettingsRepository,
    private val workerScheduler: WorkerScheduler,
) {
    private val initialized = AtomicBoolean(false)

    suspend fun initializeIfReady(readiness: DatabaseReadiness) {
        if (readiness != DatabaseReadiness.Ready || !initialized.compareAndSet(false, true)) return

        try {
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

            val backupSchedule = settingsRepository.backupSchedule.first()
            val backgroundSyncEnabled = settingsRepository.backgroundSyncEnabled.first()
            val periodicSyncMinutes =
                if (backgroundSyncEnabled) {
                    settingsRepository.backgroundSyncIntervalMinutes.first()
                } else {
                    null
                }
            workerScheduler.scheduleBackupWorker(backupSchedule)
            workerScheduler.scheduleBirthdayWorker()
            workerScheduler.scheduleDataCleanupWorker()
            if (periodicSyncMinutes != null) {
                workerScheduler.schedulePeriodicSync(periodicSyncMinutes.toLong())
            } else {
                workerScheduler.cancelPeriodicSync()
            }
        } catch (e: CancellationException) {
            initialized.set(false)
            throw e
        } catch (e: Exception) {
            initialized.set(false)
            logE(TAG, e) { "Database-ready startup initialization failed" }
        }
    }

    private companion object {
        const val TAG = "HealthDashboardApplication"
    }
}
