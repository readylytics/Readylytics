package com.gregor.lauritz.healthdashboard.workers

import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.gregor.lauritz.healthdashboard.BuildConfig
import com.gregor.lauritz.healthdashboard.domain.sync.ForegroundSyncController
import com.gregor.lauritz.healthdashboard.domain.sync.FullHistoricalResyncUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Durable, long-running worker that performs the full historical Health Connect resync triggered by
 * the Settings "Resync Health Connect data" button. Runs as a foreground service (data-sync type) so
 * it survives the app being backgrounded, shows a determinate "day X of Y" notification, publishes
 * progress for the in-app banner via [ForegroundSyncController], and exposes progress through
 * WorkInfo so the Settings screen can render it.
 */
@HiltWorker
class HealthResyncWorker
    @AssistedInject
    constructor(
        @Assisted private val appContext: Context,
        @Assisted params: WorkerParameters,
        private val fullHistoricalResyncUseCase: FullHistoricalResyncUseCase,
        private val foregroundSyncController: ForegroundSyncController,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            SyncNotifications.ensureChannel(appContext)
            runCatching { setForeground(buildForegroundInfo(0, 0)) }

            foregroundSyncController.onBackgroundRecalcStarted()
            var success = false
            return try {
                val result =
                    fullHistoricalResyncUseCase.execute { current, total ->
                        setProgressAsync(workDataOf(KEY_CURRENT to current, KEY_TOTAL to total))
                        foregroundSyncController.onBackgroundRecalcProgress(current, total)
                        runCatching {
                            NotificationManagerCompat
                                .from(appContext)
                                .notify(
                                    SyncNotifications.NOTIFICATION_ID,
                                    SyncNotifications.buildProgressNotification(appContext, current, total),
                                )
                        }
                    }

                if (result.isSuccess) {
                    success = true
                    Result.success()
                } else {
                    // Transient HC/IO failure: let WorkManager retry with its backoff policy.
                    Result.retry()
                }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e(TAG, "Resync worker failed", e)
                Result.retry()
            } finally {
                foregroundSyncController.onBackgroundRecalcFinished(success)
            }
        }

        override suspend fun getForegroundInfo(): ForegroundInfo {
            SyncNotifications.ensureChannel(appContext)
            return buildForegroundInfo(0, 0)
        }

        private fun buildForegroundInfo(
            current: Int,
            total: Int,
        ): ForegroundInfo =
            ForegroundInfo(
                SyncNotifications.NOTIFICATION_ID,
                SyncNotifications.buildProgressNotification(appContext, current, total),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )

        companion object {
            private const val TAG = "HealthResyncWorker"
            const val KEY_CURRENT = "current"
            const val KEY_TOTAL = "total"
        }
    }
