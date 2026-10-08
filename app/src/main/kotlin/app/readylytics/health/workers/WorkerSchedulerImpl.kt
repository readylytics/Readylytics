package app.readylytics.health.workers

import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import app.readylytics.health.core.model.domain.scoring.TrainingReadinessConfig
import app.readylytics.health.core.model.domain.sync.RecalcTrigger
import app.readylytics.health.core.model.domain.sync.ResyncCheckpointStore
import app.readylytics.health.core.model.workers.PeriodicWorkScheduler
import app.readylytics.health.core.model.workers.WorkerScheduler
import dagger.Lazy
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkerSchedulerImpl
    constructor(
        private val workManager: Lazy<WorkManager>,
        private val resyncCheckpointStore: Lazy<ResyncCheckpointStore>,
        periodic: PeriodicWorkScheduler,
    ) : WorkerScheduler,
        PeriodicWorkScheduler by periodic {
        // Dagger resolves this constructor: `periodic` (see the primary constructor above) has no
        // binding of its own -- it is always the real WorkManager-backed implementation, computed
        // here rather than left as an injectable parameter, purely so WorkerSchedulerImplTest's
        // existing 2-arg construction keeps working (WP-17/HC-102, boyscout TooManyFunctions split).
        @Inject
        constructor(
            workManager: Lazy<WorkManager>,
            resyncCheckpointStore: Lazy<ResyncCheckpointStore>,
        ) : this(workManager, resyncCheckpointStore, PeriodicWorkSchedulerImpl(workManager))

        companion object {
            const val RESYNC_WORK_NAME = WorkerScheduler.RESYNC_WORK_NAME
            const val DATABASE_MIGRATION_WORK_NAME = WorkerScheduler.DATABASE_MIGRATION_WORK_NAME

            /** WorkManager input `Data` is capped at 10 KB; a diagnostic detail never needs more. */
            private const val MAX_DETAIL_CHARS = 1_000
        }

        override fun scheduleDatabaseMigration() {
            val request =
                OneTimeWorkRequestBuilder<DatabaseMigrationWorker>()
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()

            workManager.get().enqueueUniqueWork(
                DATABASE_MIGRATION_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        /**
         * Enqueues the historical Health Connect resync (or, if [recomputeOnly], the SCORE-007
         * recompute-only pass) as a unique one-time foreground worker. Full resyncs use
         * [ExistingWorkPolicy.KEEP], while settings changes append a durable successor with
         * [ExistingWorkPolicy.APPEND_OR_REPLACE]. Rapid settings changes may queue redundant local
         * passes, but the final queued pass captures the newest preferences without silently losing
         * a request. Expedited so it starts promptly when explicitly requested.
         *
         * R2-CACHE-001: [startDate]/[endDate], when both provided, carry a bounded recompute-only
         * range (e.g. from `ScoreInvalidation.dependencyClosure`) through to
         * [HealthResyncWorker]/`FullHistoricalResyncUseCase`. Left `null` (the default), the
         * recompute-only pass keeps its prior full-retention-window behavior.
         *
         * WP-10 review fix: also reads the currently saved [ResyncCheckpointStore] checkpoint (if
         * any) and, when it carries an immutable run identity, threads that same
         * [HealthResyncWorker.KEY_RUN_ID] into the enqueued input data so a resumed/re-enqueued
         * request references the existing saved run instead of `HistoricalRunResolver` always
         * minting a fresh one. Absent a saved checkpoint (first-ever run), the key is simply omitted
         * -- `HistoricalRunIdentity.create` falls back to a fresh UUID.
         */
        override suspend fun scheduleResyncWorker(
            recomputeOnly: Boolean,
            startDate: LocalDate?,
            endDate: LocalDate?,
            trigger: RecalcTrigger,
            triggerDetail: String?,
        ) {
            val dataBuilder =
                Data
                    .Builder()
                    .putBoolean(HealthResyncWorker.KEY_RECOMPUTE_ONLY, recomputeOnly)
                    .putString(HealthResyncWorker.KEY_TRIGGER, trigger.name)
            triggerDetail?.let {
                dataBuilder.putString(HealthResyncWorker.KEY_TRIGGER_DETAIL, it.take(MAX_DETAIL_CHARS))
            }
            startDate?.let { dataBuilder.putLong(HealthResyncWorker.KEY_RECOMPUTE_START_EPOCH_DAY, it.toEpochDay()) }
            endDate?.let { dataBuilder.putLong(HealthResyncWorker.KEY_RECOMPUTE_END_EPOCH_DAY, it.toEpochDay()) }
            resyncCheckpointStore.get().checkpoint.first()?.runIdentity?.runId?.let {
                dataBuilder.putString(HealthResyncWorker.KEY_RUN_ID, it)
            }

            val request =
                OneTimeWorkRequestBuilder<HealthResyncWorker>()
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .setInputData(dataBuilder.build())
                    .build()

            val existingWorkPolicy =
                if (recomputeOnly) {
                    ExistingWorkPolicy.APPEND_OR_REPLACE
                } else {
                    ExistingWorkPolicy.KEEP
                }

            workManager.get().enqueueUniqueWork(
                RESYNC_WORK_NAME,
                existingWorkPolicy,
                request,
            )
        }

        override fun cancelResyncWorker() {
            workManager.get().cancelUniqueWork(RESYNC_WORK_NAME)
        }

        override fun scheduleSelectedWorkoutRepair() {
            val data =
                Data
                    .Builder()
                    .putString(HealthResyncWorker.KEY_RECOMPUTE_MODE, HealthResyncWorker.MODE_SELECTED_WORKOUT_REPAIR)
                    .build()

            val request =
                OneTimeWorkRequestBuilder<HealthResyncWorker>()
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .setInputData(data)
                    .build()

            workManager.get().enqueueUniqueWork(
                RESYNC_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        /**
         * Task 4: enqueues the durable, parameter-only Training Readiness projection recompute
         * (settings explicit "Recalculate" action, task 5) into the same unique [RESYNC_WORK_NAME]
         * chain, always [ExistingWorkPolicy.APPEND_OR_REPLACE] -- a projection request never needs
         * [ExistingWorkPolicy.KEEP] since, unlike a full resync, it carries no Health Connect
         * ingestion to protect from being superseded.
         */
        override fun scheduleTrainingReadinessRecompute(config: TrainingReadinessConfig) {
            val data =
                Data
                    .Builder()
                    .putString(HealthResyncWorker.KEY_RECOMPUTE_MODE, HealthResyncWorker.MODE_TRAINING_READINESS)
                    .putFloat(HealthResyncWorker.KEY_TRAINING_READINESS_SCALE, config.residualFatigueScale)
                    .putFloat(HealthResyncWorker.KEY_TRAINING_READINESS_WEIGHT, config.loadBalanceWeight)
                    .build()

            val request =
                OneTimeWorkRequestBuilder<HealthResyncWorker>()
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .setInputData(data)
                    .build()

            workManager.get().enqueueUniqueWork(
                RESYNC_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }
    }
