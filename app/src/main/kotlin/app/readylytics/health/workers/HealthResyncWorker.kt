package app.readylytics.health.workers

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.readylytics.health.domain.repository.HealthConnectPermissionRevokedException
import app.readylytics.health.domain.sync.ForegroundSyncController
import app.readylytics.health.domain.sync.FullHistoricalResyncUseCase
import app.readylytics.health.domain.sync.ResyncPhase
import app.readylytics.health.domain.util.logE
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

/**
 * Durable, long-running worker performing either a full historical Health Connect resync (Settings
 * "Resync Health Connect data" button) or a recompute-only pass (SCORE-007: a historical-scope
 * settings change like the TRIMP model or HR zones, signaled via [KEY_RECOMPUTE_ONLY] input data —
 * see [FullHistoricalResyncUseCase]). Runs as a foreground service (data-sync type) so it survives
 * the app being backgrounded, shows a determinate "day X of Y" notification, publishes progress for
 * the in-app banner via [ForegroundSyncController], and exposes progress through WorkInfo so the
 * Settings screen can render it. Retries resume from the persisted resync checkpoint.
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
        // Progress notifications are best-effort (wrapped in runCatching); POST_NOTIFICATIONS is
        // declared in the manifest and a missing runtime grant simply drops the update.
        @SuppressLint("MissingPermission")
        override suspend fun doWork(): Result {
            SyncNotifications.ensureChannel(appContext)
            runCatching { setForeground(buildForegroundInfo(null, 0, 0)) }

            foregroundSyncController.onBackgroundRecalcStarted()
            val recomputeOnly = inputData.getBoolean(KEY_RECOMPUTE_ONLY, false)
            var success = false
            return try {
                val result =
                    fullHistoricalResyncUseCase.execute(recomputeOnly = recomputeOnly) { phase, current, total ->
                        setProgressAsync(workDataOf(KEY_CURRENT to current, KEY_TOTAL to total))
                        foregroundSyncController.onBackgroundRecalcProgress(phase, current, total)
                        runCatching {
                            NotificationManagerCompat
                                .from(appContext)
                                .notify(
                                    SyncNotifications.NOTIFICATION_ID,
                                    SyncNotifications.buildProgressNotification(appContext, phase, current, total),
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
            } catch (e: TimeoutCancellationException) {
                Result.retry()
            } catch (e: CancellationException) {
                throw e
            } catch (e: HealthConnectPermissionRevokedException) {
                logE(TAG, e) { "Resync worker stopped: Health Connect permission failure" }
                Result.failure()
            } catch (e: Exception) {
                logE(TAG, e) { "Resync worker failed" }
                Result.retry()
            } finally {
                foregroundSyncController.onBackgroundRecalcFinished(success)
            }
        }

        override suspend fun getForegroundInfo(): ForegroundInfo {
            SyncNotifications.ensureChannel(appContext)
            return buildForegroundInfo(null, 0, 0)
        }

        private fun buildForegroundInfo(
            phase: ResyncPhase?,
            current: Int,
            total: Int,
        ): ForegroundInfo =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                ForegroundInfo(
                    SyncNotifications.NOTIFICATION_ID,
                    SyncNotifications.buildProgressNotification(appContext, phase, current, total),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                ForegroundInfo(
                    SyncNotifications.NOTIFICATION_ID,
                    SyncNotifications.buildProgressNotification(appContext, phase, current, total),
                )
            }

        companion object {
            private const val TAG = "HealthResyncWorker"
            const val KEY_CURRENT = "current"
            const val KEY_TOTAL = "total"

            /** Input data key: true routes this run through the SCORE-007 recompute-only path. */
            const val KEY_RECOMPUTE_ONLY = "recompute_only"
        }
    }
