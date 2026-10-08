package app.readylytics.health.workers

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.readylytics.health.core.model.data.preferences.BackupSchedule
import app.readylytics.health.core.model.workers.PeriodicWorkScheduler
import app.readylytics.health.core.model.workers.WorkerScheduler
import dagger.Lazy
import java.util.concurrent.TimeUnit

/**
 * WP-17 (HC-102): the six recurring `PeriodicWorkRequest` schedulers, split out of
 * [WorkerSchedulerImpl] purely to keep both it and [WorkerScheduler] under detekt's
 * `TooManyFunctions` threshold -- see [PeriodicWorkScheduler]'s doc comment. [WorkerSchedulerImpl]
 * delegates to an instance of this class via Kotlin interface delegation (`by`), so these methods
 * are still reachable through the single [WorkerScheduler] type every other caller already injects.
 */
class PeriodicWorkSchedulerImpl(
    private val workManager: Lazy<WorkManager>,
) : PeriodicWorkScheduler {
    override fun scheduleBackupWorker(schedule: BackupSchedule) {
        if (schedule == BackupSchedule.MANUAL) {
            workManager.get().cancelUniqueWork(WorkerScheduler.LOCAL_BACKUP_WORK_NAME)
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
            WorkerScheduler.LOCAL_BACKUP_WORK_NAME,
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
            WorkerScheduler.BIRTHDAY_WORK_NAME,
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
            WorkerScheduler.PERIODIC_SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    override fun cancelPeriodicSync() {
        workManager.get().cancelUniqueWork(WorkerScheduler.PERIODIC_SYNC_WORK_NAME)
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
            WorkerScheduler.DATA_CLEANUP_WORK_NAME,
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
            WorkerScheduler.DATA_ROLLUP_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
