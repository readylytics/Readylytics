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
import app.readylytics.health.core.model.domain.preferences.scoringZone
import app.readylytics.health.core.model.domain.repository.HealthConnectPermissionRevokedException
import app.readylytics.health.core.model.domain.scoring.TrainingReadinessConfig
import app.readylytics.health.core.model.domain.sync.DirtyRangeStore
import app.readylytics.health.core.model.domain.sync.ResyncPhase
import app.readylytics.health.core.model.domain.sync.ScoreInvalidation
import app.readylytics.health.core.model.domain.util.RetentionBounds
import app.readylytics.health.core.model.domain.util.logE
import dagger.Lazy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import java.time.LocalDate

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
        private val dirtyRangeStore: Lazy<DirtyRangeStore>,
    ) : CoroutineWorker(appContext, params) {
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

        private suspend fun runTrainingReadinessProjection(
            resyncUseCase: FullHistoricalResyncUseCase,
            syncController: ForegroundSyncController,
            onSuccessChanged: (Boolean) -> Unit,
        ): Result {
            val scale = inputData.getString(KEY_TRAINING_READINESS_SCALE) ?: return Result.failure()
            val weight = inputData.getString(KEY_TRAINING_READINESS_WEIGHT) ?: return Result.failure()
            val config = TrainingReadinessConfig(scale, weight)

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

        @SuppressLint("MissingPermission")
        private suspend fun runNormalRecompute(
            resyncUseCase: FullHistoricalResyncUseCase,
            syncController: ForegroundSyncController,
            onSuccessChanged: (Boolean) -> Unit,
        ): Result {
            val recomputeOnly = inputData.getBoolean(KEY_RECOMPUTE_ONLY, false)
            val runId = inputData.getString(KEY_RUN_ID)
            val prefs = settingsRepository.get().userPreferences.first()
            val retentionStart = RetentionBounds.resolveResyncStartDate(prefs, LocalDate.now(prefs.scoringZone()))
            dirtyRangeStore.get().discardBefore(retentionStart)

            while (true) {
                val rangeOverride = resolveRecomputeRange(recomputeOnly)
                if (recomputeOnly && rangeOverride == null) {
                    break
                }

                val result =
                    resyncUseCase.execute(
                        recomputeOnly = recomputeOnly,
                        rangeOverride = rangeOverride,
                        runId = runId,
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

                if (result.isSuccess) {
                    persistPostRecomputeState(recomputeOnly = recomputeOnly, rangeOverride = rangeOverride)
                    if (!recomputeOnly) break
                } else {
                    return Result.retry()
                }
            }
            onSuccessChanged(true)
            return Result.success()
        }

        private suspend fun resolveRecomputeRange(recomputeOnly: Boolean): ScoreInvalidation.AffectedRange? {
            val explicitStart = inputData.getLong(KEY_RECOMPUTE_START_EPOCH_DAY, -1L)
            if (explicitStart >= 0) {
                val end = inputData.getLong(KEY_RECOMPUTE_END_EPOCH_DAY, explicitStart)
                return ScoreInvalidation.AffectedRange(LocalDate.ofEpochDay(explicitStart), LocalDate.ofEpochDay(end))
            }
            val pending = if (recomputeOnly) dirtyRangeStore.get().pending(100) else emptyList()
            return pending.takeIf { it.isNotEmpty() }?.let {
                ScoreInvalidation.AffectedRange(
                    it.minOf { ticket -> ticket.nextDay },
                    it.maxOf { ticket -> ticket.endInclusive },
                )
            }
        }

        private suspend fun persistPostRecomputeState(
            recomputeOnly: Boolean,
            rangeOverride: ScoreInvalidation.AffectedRange?,
        ) {
            try {
                val settings = settingsRepository.get()
                val prefs = settings.userPreferences.first()
                if (coversRetainedHistory(recomputeOnly, rangeOverride, prefs) &&
                    prefs.scoringVersion < SettingsDefaults.CURRENT_SCORING_VERSION
                ) {
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

        private fun coversRetainedHistory(
            recomputeOnly: Boolean,
            rangeOverride: ScoreInvalidation.AffectedRange?,
            prefs: UserPreferences,
        ): Boolean {
            if (!recomputeOnly || rangeOverride == null) return true
            val today = LocalDate.now(prefs.scoringZone())
            val retentionStart = RetentionBounds.resolveResyncStartDate(prefs, today)
            return !rangeOverride.start.isAfter(retentionStart) && !rangeOverride.endInclusive.isBefore(today)
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
            const val KEY_RECOMPUTE_ONLY = "recompute_only"
            const val KEY_RUN_ID = "run_id"
            const val KEY_RECOMPUTE_START_EPOCH_DAY = "recompute_start_epoch_day"
            const val KEY_RECOMPUTE_END_EPOCH_DAY = "recompute_end_epoch_day"
            const val KEY_RECOMPUTE_MODE = "recompute_mode"
            const val MODE_TRAINING_READINESS = "TRAINING_READINESS"
            const val KEY_TRAINING_READINESS_SCALE = "training_readiness_scale"
            const val KEY_TRAINING_READINESS_WEIGHT = "training_readiness_weight"
        }
    }
