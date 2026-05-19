package com.gregor.lauritz.healthdashboard.workers

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.gregor.lauritz.healthdashboard.data.preferences.BackupSchedule
import dagger.Lazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkerScheduler
    @Inject
    constructor(
        private val workManager: Lazy<WorkManager>,
    ) {
        companion object {
            const val LOCAL_BACKUP_WORK_NAME = "local_backup_periodic"
            const val BIRTHDAY_WORK_NAME = "birthday_check_periodic"
            const val DATA_CLEANUP_WORK_NAME = "data_cleanup_periodic"
        }

        suspend fun scheduleBackupWorker(schedule: BackupSchedule) =
            withContext(Dispatchers.IO) {
                if (schedule == BackupSchedule.MANUAL) {
                    workManager.get().cancelUniqueWork(LOCAL_BACKUP_WORK_NAME)
                    return@withContext
                }

                val intervalDays = if (schedule == BackupSchedule.DAILY) 1L else 7L
                val constraints =
                    Constraints
                        .Builder()
                        .setRequiresBatteryNotLow(true)
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

        suspend fun scheduleBirthdayWorker() =
            withContext(Dispatchers.IO) {
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

        suspend fun scheduleDataCleanupWorker() =
            withContext(Dispatchers.IO) {
                val constraints =
                    Constraints
                        .Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()

                val request =
                    PeriodicWorkRequestBuilder<DataCleanupWorker>(1, TimeUnit.DAYS)
                        .setConstraints(constraints)
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
                        .build()

                workManager.get().enqueueUniquePeriodicWork(
                    DATA_CLEANUP_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
            }
    }
