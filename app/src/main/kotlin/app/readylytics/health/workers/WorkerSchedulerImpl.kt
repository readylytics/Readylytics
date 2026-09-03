package app.readylytics.health.workers

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.readylytics.health.core.model.data.preferences.BackupSchedule
import app.readylytics.health.core.model.domain.scoring.TrainingReadinessConfig
import app.readylytics.health.core.model.workers.WorkerScheduler
import dagger.Lazy
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkerSchedulerImpl
    @Inject
    constructor(
        private val workManager: Lazy<WorkManager>,
    ) : WorkerScheduler {
        companion object {
            const val LOCAL_BACKUP_WORK_NAME = WorkerScheduler.LOCAL_BACKUP_WORK_NAME
            const val BIRTHDAY_WORK_NAME = WorkerScheduler.BIRTHDAY_WORK_NAME
            const val DATA_CLEANUP_WORK_NAME = WorkerScheduler.DATA_CLEANUP_WORK_NAME
            const val DATA_ROLLUP_WORK_NAME = WorkerScheduler.DATA_ROLLUP_WORK_NAME
            const val RESYNC_WORK_NAME = WorkerScheduler.RESYNC_WORK_NAME
            const val PERIODIC_SYNC_WORK_NAME = WorkerScheduler.PERIODIC_SYNC_WORK_NAME
            const val DATABASE_MIGRATION_WORK_NAME = WorkerScheduler.DATABASE_MIGRATION_WORK_NAME
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
         * range (e.g. from `ScoreInvalidation.affectedRange`) through to
         * [HealthResyncWorker]/`FullHistoricalResyncUseCase`. Left `null` (the default), the
         * recompute-only pass keeps its prior full-retention-window behavior.
         */
        override fun scheduleResyncWorker(
            recomputeOnly: Boolean,
            startDate: LocalDate?,
            endDate: LocalDate?,
        ) {
            val dataBuilder = Data.Builder().putBoolean(HealthResyncWorker.KEY_RECOMPUTE_ONLY, recomputeOnly)
            startDate?.let { dataBuilder.putLong(HealthResyncWorker.KEY_RECOMPUTE_START_EPOCH_DAY, it.toEpochDay()) }
            endDate?.let { dataBuilder.putLong(HealthResyncWorker.KEY_RECOMPUTE_END_EPOCH_DAY, it.toEpochDay()) }

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

        override fun scheduleBackupWorker(schedule: BackupSchedule) {
            if (schedule == BackupSchedule.MANUAL) {
                workManager.get().cancelUniqueWork(LOCAL_BACKUP_WORK_NAME)
                return
            }

            val intervalDays = if (schedule == BackupSchedule.DAILY) 1L else 7L
            val constraints =
                Constraints
                    .Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiresCharging(true)
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

            val request =
                PeriodicWorkRequestBuilder<LocalBackupWorker>(intervalDays, TimeUnit.DAYS)
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                    .build()

            workManager.get().enqueueUniquePeriodicWork(
                LOCAL_BACKUP_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        override fun scheduleBirthdayWorker() {
            val constraints =
                Constraints
                    .Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()

            val request =
                PeriodicWorkRequestBuilder<BirthdayCheckWorker>(1, TimeUnit.DAYS)
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
                    .build()

            workManager.get().enqueueUniquePeriodicWork(
                BIRTHDAY_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /**
         * Enqueues (or reschedules with a new interval) the periodic background Health Connect
         * sync. [ExistingPeriodicWorkPolicy.UPDATE] applies the new interval immediately while
         * preserving the unique work identity.
         */
        override fun schedulePeriodicSync(intervalMinutes: Long) {
            val constraints =
                Constraints
                    .Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()

            val request =
                PeriodicWorkRequestBuilder<PeriodicHealthSyncWorker>(intervalMinutes, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                    .build()

            workManager.get().enqueueUniquePeriodicWork(
                PERIODIC_SYNC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        override fun cancelPeriodicSync() {
            workManager.get().cancelUniqueWork(PERIODIC_SYNC_WORK_NAME)
        }

        override fun scheduleDataCleanupWorker() {
            val constraints =
                Constraints
                    .Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiresDeviceIdle(true)
                    .build()

            val request =
                PeriodicWorkRequestBuilder<DataCleanupWorker>(1, TimeUnit.DAYS)
                    .setConstraints(constraints)
                    .build()

            workManager.get().enqueueUniquePeriodicWork(
                DATA_CLEANUP_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        override fun scheduleDataRollupWorker() {
            val constraints =
                Constraints
                    .Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiresDeviceIdle(true)
                    .build()

            val request =
                PeriodicWorkRequestBuilder<DataRollupWorker>(1, TimeUnit.DAYS)
                    .setConstraints(constraints)
                    .build()

            workManager.get().enqueueUniquePeriodicWork(
                DATA_ROLLUP_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
