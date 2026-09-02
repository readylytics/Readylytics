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
import app.readylytics.health.core.healthconnect.domain.sync.ForegroundSyncController
import app.readylytics.health.core.healthconnect.domain.sync.FullHistoricalResyncUseCase
import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import app.readylytics.health.core.model.data.preferences.appliedTrainingReadinessConfig
import app.readylytics.health.core.model.domain.migration.DatabaseReadiness
import app.readylytics.health.core.model.domain.migration.DatabaseReadinessInspector
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.HealthConnectPermissionRevokedException
import app.readylytics.health.core.model.domain.scoring.TrainingReadinessConfig
import app.readylytics.health.core.model.domain.sync.ResyncPhase
import app.readylytics.health.core.model.domain.sync.ScoreInvalidation
import app.readylytics.health.core.model.domain.util.logE
import dagger.Lazy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * Durable, long-running worker performing one of: a full historical Health Connect resync
 * (Settings "Resync Health Connect data" button), a recompute-only pass (SCORE-007: a
 * historical-scope settings change like the TRIMP model or HR zones, signaled via
 * [KEY_RECOMPUTE_ONLY] input data — see [FullHistoricalResyncUseCase]), or a durable,
 * parameter-only Training Readiness projection recompute (task 4: [KEY_RECOMPUTE_MODE] ==
 * [MODE_TRAINING_READINESS] — see [runTrainingReadinessProjection]). Runs as a foreground service
 * (data-sync type) so it survives the app being backgrounded, shows a determinate "day X of Y"
 * notification, publishes progress for the in-app banner via [ForegroundSyncController], and
 * exposes progress through WorkInfo so the Settings screen can render it. Retries resume from the
 * persisted resync checkpoint (the training-readiness path is idempotent by construction and has
 * no checkpoint of its own).
 */
@HiltWorker
class HealthResyncWorker
    @AssistedInject
    constructor(
        @Assisted private val appContext: Context,
        @Assisted params: WorkerParameters,
        private val fullHistoricalResyncUseCase: Lazy<FullHistoricalResyncUseCase>,
        private val foregroundSyncController: Lazy<ForegroundSyncController>,
        private val databaseReadinessGate: DatabaseReadinessInspector,
        private val settingsRepository: Lazy<SettingsRepository>,
    ) : CoroutineWorker(appContext, params) {
        // Progress notifications (posted from runNormalRecompute/runTrainingReadinessProjection)
        // are best-effort (wrapped in runCatching); POST_NOTIFICATIONS is declared in the manifest
        // and a missing runtime grant simply drops the update.
        override suspend fun doWork(): Result {
            if (databaseReadinessGate.inspect() != DatabaseReadiness.Ready) {
                return Result.retry()
            }
            val resyncUseCase = fullHistoricalResyncUseCase.get()
            val syncController = foregroundSyncController.get()
            SyncNotifications.ensureChannel(appContext)
            runCatching { setForeground(buildForegroundInfo(null, 0, 0)) }

            syncController.onBackgroundRecalcStarted()
            var success = false
            return try {
                if (inputData.getString(KEY_RECOMPUTE_MODE) == MODE_TRAINING_READINESS) {
                    runTrainingReadinessProjection(resyncUseCase, syncController) { success = it }
                } else {
                    runNormalRecompute(resyncUseCase, syncController) { success = it }
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
                syncController.onBackgroundRecalcFinished(success)
            }
        }

        /**
         * Today's [KEY_RECOMPUTE_ONLY]-driven path (full resync or a bounded settings recompute) --
         * unchanged behavior, extracted out of [doWork] so [MODE_TRAINING_READINESS] can share this
         * worker/unique work chain without touching this branch (task 4). Progress notifications
         * are best-effort (wrapped in runCatching); see the class-level POST_NOTIFICATIONS note.
         */
        @SuppressLint("MissingPermission")
        private suspend fun runNormalRecompute(
            resyncUseCase: FullHistoricalResyncUseCase,
            syncController: ForegroundSyncController,
            onSuccessChanged: (Boolean) -> Unit,
        ): Result {
            val recomputeOnly = inputData.getBoolean(KEY_RECOMPUTE_ONLY, false)
            val rangeOverride =
                inputData.getLong(KEY_RECOMPUTE_START_EPOCH_DAY, -1L).takeIf { it >= 0 }?.let { startEpochDay ->
                    val endEpochDay = inputData.getLong(KEY_RECOMPUTE_END_EPOCH_DAY, startEpochDay)
                    ScoreInvalidation.AffectedRange(
                        start = LocalDate.ofEpochDay(startEpochDay),
                        endInclusive = LocalDate.ofEpochDay(endEpochDay),
                    )
                }
            val result =
                resyncUseCase.execute(
                    recomputeOnly = recomputeOnly,
                    rangeOverride = rangeOverride,
                ) { phase, current, total ->
                    setProgressAsync(workDataOf(KEY_CURRENT to current, KEY_TOTAL to total))
                    syncController.onBackgroundRecalcProgress(phase, current, total)
                    runCatching {
                        NotificationManagerCompat
                            .from(appContext)
                            .notify(
                                SyncNotifications.NOTIFICATION_ID,
                                SyncNotifications.buildProgressNotification(appContext, phase, current, total),
                            )
                    }
                }

            return if (result.isSuccess) {
                onSuccessChanged(true)
                persistPostRecomputeState()
                Result.success()
            } else {
                // Transient HC/IO failure: let WorkManager retry with its backoff policy.
                Result.retry()
            }
        }

        /**
         * Task 4: durable, parameter-only Training Readiness projection recompute (Settings
         * explicit "Recalculate" action, task 5). Decodes the requested S/w pair from input data via
         * [TrainingReadinessConfig.fromStored] (repairs corrupt values rather than failing) and
         * delegates to [FullHistoricalResyncUseCase.executeTrainingReadinessProjection] -- no Health
         * Connect I/O, no [persistPostRecomputeState]. Only after the projection transaction commits
         * does this unconditionally advance the *applied* preference pair to the requested
         * [TrainingReadinessConfig]; a failure here -- including the preference write itself --
         * falls through to [doWork]'s outer catch/retry, leaving the previously applied
         * configuration (and the Settings screen's pending indicator) untouched. Progress
         * notifications are best-effort (wrapped in runCatching); see the class-level
         * POST_NOTIFICATIONS note.
         */
        @SuppressLint("MissingPermission")
        private suspend fun runTrainingReadinessProjection(
            resyncUseCase: FullHistoricalResyncUseCase,
            syncController: ForegroundSyncController,
            onSuccessChanged: (Boolean) -> Unit,
        ): Result {
            val config =
                TrainingReadinessConfig.fromStored(
                    inputData.getFloat(
                        KEY_TRAINING_READINESS_SCALE,
                        SettingsDefaults.TRAINING_READINESS_RESIDUAL_FATIGUE_SCALE,
                    ),
                    inputData.getFloat(
                        KEY_TRAINING_READINESS_WEIGHT,
                        SettingsDefaults.TRAINING_READINESS_LOAD_BALANCE_WEIGHT,
                    ),
                )
            val result =
                resyncUseCase.executeTrainingReadinessProjection(config) { current, total ->
                    setProgressAsync(workDataOf(KEY_CURRENT to current, KEY_TOTAL to total))
                    syncController.onBackgroundRecalcProgress(ResyncPhase.RECOMPUTE, current, total)
                    runCatching {
                        NotificationManagerCompat
                            .from(appContext)
                            .notify(
                                SyncNotifications.NOTIFICATION_ID,
                                SyncNotifications.buildProgressNotification(
                                    appContext,
                                    ResyncPhase.RECOMPUTE,
                                    current,
                                    total,
                                ),
                            )
                    }
                }

            return if (result.isSuccess) {
                settingsRepository.get().updateTrainingReadinessConfig(config)
                onSuccessChanged(true)
                Result.success()
            } else {
                Result.retry()
            }
        }

        /**
         * Records that this run actually applied to history: bump [UserPreferences.scoringVersion]
         * when stale AND snapshot the sleep-scoring inputs into the `last_recalc_*` baseline. This is
         * best-effort and idempotent — a failure here cannot corrupt already-recomputed scores, and the
         * next successful resync re-runs it. The startup initializer intentionally no longer bumps the
         * version, so a killed worker leaves the stale version in place and the next launch re-enqueues.
         */
        private suspend fun persistPostRecomputeState() {
            try {
                val settings = settingsRepository.get()
                val prefs = settings.userPreferences.first()
                if (prefs.scoringVersion < SettingsDefaults.CURRENT_SCORING_VERSION) {
                    settings.updateScoringVersion(SettingsDefaults.CURRENT_SCORING_VERSION)
                }
                settings.updateSleepScoreRecalcBaseline(
                    weightProfile = prefs.sleepScoreWeightProfile,
                    goalSleepHours = prefs.goalSleepHours,
                    hypersomniaOnsetPercent = prefs.hypersomniaOnsetPercent,
                )
                if (prefs.lastAppliedTrainingReadinessResidualFatigueScale == null ||
                    prefs.lastAppliedTrainingReadinessLoadBalanceWeight == null
                ) {
                    settings.updateTrainingReadinessConfig(prefs.appliedTrainingReadinessConfig())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logE(TAG, e) { "Failed to persist post-recompute scoring version/baseline" }
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

            /**
             * R2-CACHE-001: optional input data keys carrying a bounded recompute-only date-range
             * override (epoch days). Absent (or [KEY_RECOMPUTE_START_EPOCH_DAY] negative) means "no
             * override" -- a recompute-only pass covers the full retention window, as before.
             */
            const val KEY_RECOMPUTE_START_EPOCH_DAY = "recompute_start_epoch_day"
            const val KEY_RECOMPUTE_END_EPOCH_DAY = "recompute_end_epoch_day"

            /**
             * Task 4: input data key routing this run through [runTrainingReadinessProjection]
             * instead of the [KEY_RECOMPUTE_ONLY]-based [runNormalRecompute] path when its value is
             * exactly [MODE_TRAINING_READINESS]. Absent, `null`, or any other value is treated as
             * "absent" and falls through to the unchanged normal-recompute branch -- a malformed
             * mode never accidentally becomes a training-readiness run.
             */
            const val KEY_RECOMPUTE_MODE = "recompute_mode"
            const val MODE_TRAINING_READINESS = "TRAINING_READINESS"

            /** Task 4: the requested (not yet applied) Training Readiness S/w pair to project. */
            const val KEY_TRAINING_READINESS_SCALE = "training_readiness_scale"
            const val KEY_TRAINING_READINESS_WEIGHT = "training_readiness_weight"
        }
    }
