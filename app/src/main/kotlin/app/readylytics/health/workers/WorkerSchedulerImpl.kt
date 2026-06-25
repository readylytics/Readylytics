package app.readylytics.health.workers

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.readylytics.health.data.preferences.BackupSchedule
import dagger.Lazy
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
            const val RESYNC_WORK_NAME = WorkerScheduler.RESYNC_WORK_NAME
            const val PERIODIC_SYNC_WORK_NAME = WorkerScheduler.PERIODIC_SYNC_WORK_NAME
        }

        /**
         * Enqueues the full historical Health Connect resync as a unique one-time foreground worker.
         * [ExistingWorkPolicy.KEEP] means a tap while a resync is already running is a no-op rather
         * than restarting it. Expedited so it starts promptly when the user explicitly requests it.
         */
        override fun scheduleResyncWorker() {
            val request =
                OneTimeWorkRequestBuilder<HealthResyncWorker>()
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()

            workManager.get().enqueueUniqueWork(
                RESYNC_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        override fun cancelResyncWorker() {
            workManager.get().cancelUniqueWork(RESYNC_WORK_NAME)
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
            val request =
                PeriodicWorkRequestBuilder<PeriodicHealthSyncWorker>(intervalMinutes, TimeUnit.MINUTES)
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
    }
