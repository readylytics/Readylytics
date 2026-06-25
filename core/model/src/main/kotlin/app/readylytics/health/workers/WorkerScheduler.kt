package app.readylytics.health.workers

import app.readylytics.health.data.preferences.BackupSchedule

interface WorkerScheduler {
    companion object {
        const val LOCAL_BACKUP_WORK_NAME = "local_backup_periodic"
        const val BIRTHDAY_WORK_NAME = "birthday_check_periodic"
        const val DATA_CLEANUP_WORK_NAME = "data_cleanup_periodic"
        const val RESYNC_WORK_NAME = "health_resync_onetime"
        const val PERIODIC_SYNC_WORK_NAME = "health_periodic_sync"
    }

    fun scheduleResyncWorker()
    fun cancelResyncWorker()
    fun scheduleBackupWorker(schedule: BackupSchedule)
    fun scheduleBirthdayWorker()
    fun schedulePeriodicSync(intervalMinutes: Long)
    fun cancelPeriodicSync()
    fun scheduleDataCleanupWorker()
}
