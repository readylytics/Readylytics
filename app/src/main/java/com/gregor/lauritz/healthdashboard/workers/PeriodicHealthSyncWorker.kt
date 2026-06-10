package com.gregor.lauritz.healthdashboard.workers

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.gregor.lauritz.healthdashboard.BuildConfig
import com.gregor.lauritz.healthdashboard.domain.sync.ForegroundSyncController
import com.gregor.lauritz.healthdashboard.domain.sync.HealthSyncUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Short-lived periodic worker for "Background Sync": pulls the last 2 days of Health Connect
 * data via [HealthSyncUseCase.sync], sharing [HealthSyncUseCase]'s mutex with the foreground and
 * resync flows so they never run concurrently. Shows a transient silent notification while
 * running and dismisses it (success or failure) when done.
 */
@HiltWorker
class PeriodicHealthSyncWorker
    @AssistedInject
    constructor(
        @Assisted private val appContext: Context,
        @Assisted params: WorkerParameters,
        private val healthSyncUseCase: HealthSyncUseCase,
        private val foregroundSyncController: ForegroundSyncController,
    ) : CoroutineWorker(appContext, params) {
        @SuppressLint("MissingPermission")
        override suspend fun doWork(): Result {
            SyncNotifications.ensureBackgroundSyncChannel(appContext)
            runCatching { setForeground(buildForegroundInfo()) }

            foregroundSyncController.onBackgroundRecalcStarted()
            var success = false
            return try {
                val result = healthSyncUseCase.sync(windowDays = WINDOW_DAYS)
                if (result.isSuccess) {
                    success = true
                    Result.success()
                } else {
                    Result.retry()
                }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e(TAG, "Periodic sync worker failed", e)
                Result.retry()
            } finally {
                foregroundSyncController.onBackgroundRecalcFinished(success)
                runCatching {
                    NotificationManagerCompat.from(appContext).cancel(SyncNotifications.BACKGROUND_SYNC_NOTIFICATION_ID)
                }
            }
        }

        override suspend fun getForegroundInfo(): ForegroundInfo {
            SyncNotifications.ensureBackgroundSyncChannel(appContext)
            return buildForegroundInfo()
        }

        private fun buildForegroundInfo(): ForegroundInfo =
            ForegroundInfo(
                SyncNotifications.BACKGROUND_SYNC_NOTIFICATION_ID,
                SyncNotifications.buildBackgroundSyncNotification(appContext),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )

        companion object {
            private const val TAG = "PeriodicHealthSyncWorker"
            private const val WINDOW_DAYS = 2
        }
    }
