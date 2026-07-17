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

    /**
     * @param recomputeOnly SCORE-007: true routes the durable worker through a recompute-only pass
     *   (skips Health Connect re-ingestion) for a historical-scope settings change; false (default)
     *   is the full historical resync from the Settings button. Both share this one unique
     *   `RESYNC_WORK_NAME` work slot (kept, not replaced, if one is already queued/running), so only
     *   one can be in flight at a time.
     */
    fun scheduleResyncWorker(recomputeOnly: Boolean = false)
    fun cancelResyncWorker()
    fun scheduleBackupWorker(schedule: BackupSchedule)
    fun scheduleBirthdayWorker()
    fun schedulePeriodicSync(intervalMinutes: Long)
    fun cancelPeriodicSync()
    fun scheduleDataCleanupWorker()
}
