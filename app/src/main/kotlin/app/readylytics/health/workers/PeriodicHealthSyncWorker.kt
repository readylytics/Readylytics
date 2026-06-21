package app.readylytics.health.workers

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.readylytics.health.domain.repository.HealthConnectPermissionRevokedException
import app.readylytics.health.domain.sync.ForegroundSyncController
import app.readylytics.health.domain.sync.HealthSyncUseCase
import app.readylytics.health.domain.util.logE
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import app.readylytics.health.domain.model.Result as DomainResult

/**
 * Short-lived periodic worker for "Background Sync": pulls the last 2 days of Health Connect
 * data via [HealthSyncUseCase.sync], sharing [HealthSyncUseCase]'s mutex with the foreground and
 * resync flows so they never run concurrently. Runs as a standard (non-foreground) worker — the
 * app holds READ_HEALTH_DATA_IN_BACKGROUND, so no foreground service is needed, and starting one
 * from a periodic background worker risks ForegroundServiceStartNotAllowedException on API 34+.
 * Shows a transient silent notification while running and dismisses it when done. If bounded sync
 * detects older changes, this worker enqueues the durable full historical resync worker instead
 * of retrying periodic work.
 */
@HiltWorker
class PeriodicHealthSyncWorker
    @AssistedInject
    constructor(
        @Assisted private val appContext: Context,
        @Assisted params: WorkerParameters,
        private val healthSyncUseCase: HealthSyncUseCase,
        private val foregroundSyncController: ForegroundSyncController,
        private val workerScheduler: WorkerScheduler,
    ) : CoroutineWorker(appContext, params) {
        @SuppressLint("MissingPermission")
        override suspend fun doWork(): Result {
            SyncNotifications.ensureBackgroundSyncChannel(appContext)
            val notificationManager = NotificationManagerCompat.from(appContext)
            runCatching {
                notificationManager.notify(
                    SyncNotifications.BACKGROUND_SYNC_NOTIFICATION_ID,
                    SyncNotifications.buildBackgroundSyncNotification(appContext),
                )
            }

            foregroundSyncController.onBackgroundRecalcStarted()
            var success = false
            return try {
                val result = healthSyncUseCase.sync(windowDays = WINDOW_DAYS)
                when {
                    result.isSuccess -> {
                        success = true
                        Result.success()
                    }
                    result is DomainResult.Failure &&
                        result.code == REQUIRES_HISTORICAL_RESYNC -> {
                        workerScheduler.scheduleResyncWorker()
                        Result.success()
                    }
                    else -> Result.retry()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logE(TAG, e) { "Periodic sync worker failed" }
                if (e is SecurityException || e is HealthConnectPermissionRevokedException) {
                    Result.failure()
                } else {
                    Result.retry()
                }
            } finally {
                foregroundSyncController.onBackgroundRecalcFinished(success)
                runCatching {
                    notificationManager.cancel(SyncNotifications.BACKGROUND_SYNC_NOTIFICATION_ID)
                }
            }
        }

        companion object {
            private const val TAG = "PeriodicHealthSyncWorker"
            private const val WINDOW_DAYS = 2
            private const val REQUIRES_HISTORICAL_RESYNC = "REQUIRES_HISTORICAL_RESYNC"
        }
    }
