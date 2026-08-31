package app.readylytics.health.core.model.workers

import app.readylytics.health.core.model.data.preferences.BackupSchedule
import java.time.LocalDate

interface WorkerScheduler {
    companion object {
        const val LOCAL_BACKUP_WORK_NAME = "local_backup_periodic"
        const val BIRTHDAY_WORK_NAME = "birthday_check_periodic"
        const val DATA_CLEANUP_WORK_NAME = "data_cleanup_periodic"
        const val DATA_ROLLUP_WORK_NAME = "data_rollup_periodic"
        const val RESYNC_WORK_NAME = "health_resync_onetime"
        const val PERIODIC_SYNC_WORK_NAME = "health_periodic_sync"
        const val DATABASE_MIGRATION_WORK_NAME = "database_v7_migration"
    }

    fun scheduleDatabaseMigration()
    /**
     * @param recomputeOnly SCORE-007: true routes the durable worker through a recompute-only pass
     *   (skips Health Connect re-ingestion) for a historical-scope settings change; false (default)
     *   is the full historical resync from the Settings button. Both share this one unique
     *   `RESYNC_WORK_NAME` chain. Full resyncs keep existing work, while settings changes append a
     *   durable successor. Rapid settings changes may create redundant local passes, but the final
     *   queued pass captures the newest preferences and no request is silently lost.
     * @param startDate R2-CACHE-001: optional inclusive start of a bounded recompute-only range
     *   (e.g. from `ScoreInvalidation.affectedRange`). Ignored when [recomputeOnly] is false. `null`
     *   (default) keeps the existing full-retention-window recompute behavior.
     * @param endDate R2-CACHE-001: optional inclusive end of the bounded recompute-only range.
     *   `null` (default) keeps the existing full-retention-window recompute behavior.
     */
    fun scheduleResyncWorker(
        recomputeOnly: Boolean = false,
        startDate: LocalDate? = null,
        endDate: LocalDate? = null,
    )
    fun cancelResyncWorker()
    fun scheduleBackupWorker(schedule: BackupSchedule)
    fun scheduleBirthdayWorker()
    fun schedulePeriodicSync(intervalMinutes: Long)
    fun cancelPeriodicSync()
    fun scheduleDataCleanupWorker()
    fun scheduleDataRollupWorker()
}
