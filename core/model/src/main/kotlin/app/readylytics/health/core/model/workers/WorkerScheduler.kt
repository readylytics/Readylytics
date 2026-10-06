package app.readylytics.health.core.model.workers

import app.readylytics.health.core.model.data.preferences.BackupSchedule
import app.readylytics.health.core.model.domain.scoring.TrainingReadinessConfig
import app.readylytics.health.core.model.domain.sync.RecalcTrigger
import java.time.LocalDate

/**
 * Periodic (recurring `PeriodicWorkRequest`) workers, split out of [WorkerScheduler] (WP-17/HC-102)
 * purely to keep each interface under detekt's `TooManyFunctions` threshold -- every method here is
 * still reachable through [WorkerScheduler], which extends this. [WorkerSchedulerImpl] implements
 * these via Kotlin interface delegation (`by`) to [app.readylytics.health.workers.PeriodicWorkSchedulerImpl]
 * rather than one-line override thunks, so delegation doesn't just move the function count back onto
 * that class.
 */
interface PeriodicWorkScheduler {
    fun scheduleBackupWorker(schedule: BackupSchedule)
    fun scheduleBirthdayWorker()
    fun schedulePeriodicSync(intervalMinutes: Long)
    fun cancelPeriodicSync()
    fun scheduleDataCleanupWorker()
    fun scheduleDataRollupWorker()
}

interface WorkerScheduler : PeriodicWorkScheduler {
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
     *   (e.g. from `ScoreInvalidation.dependencyClosure`). Ignored when [recomputeOnly] is false.
     *   `null` (default) keeps the existing full-retention-window recompute behavior.
     * @param endDate R2-CACHE-001: optional inclusive end of the bounded recompute-only range.
     *   `null` (default) keeps the existing full-retention-window recompute behavior.
     * @param trigger why this pass is enqueued; recorded in diagnostics when an unexpected trigger
     *   resolves to a large range. Defaults to [RecalcTrigger.SETTINGS_CHANGE].
     * @param triggerDetail optional free-text cause (e.g. the Health Connect escalation reason).
     *
     * WP-10 review fix: `suspend` so the implementation can read the currently saved
     * [app.readylytics.health.core.model.domain.sync.ResyncCheckpointStore] checkpoint (if any) and
     * thread its immutable [app.readylytics.health.core.model.domain.sync.HistoricalRunIdentity.runId]
     * into the enqueued work request -- a resumed/re-enqueued request must reference the existing
     * saved run rather than always minting a fresh one.
     */
    suspend fun scheduleResyncWorker(
        recomputeOnly: Boolean = false,
        startDate: LocalDate? = null,
        endDate: LocalDate? = null,
        trigger: RecalcTrigger = RecalcTrigger.SETTINGS_CHANGE,
        triggerDetail: String? = null,
    )
    fun cancelResyncWorker()

    /**
     * WP-17 (HC-102): enqueues the one-time repair-only pass (prune already-stranded
     * de-selected-device workouts + recompute their affected range, zero Health Connect reads)
     * into the same unique [RESYNC_WORK_NAME] chain. Always [androidx.work.ExistingWorkPolicy.KEEP]
     * -- unlike a settings-change recompute, a second startup before the first repair finishes
     * must never double-enqueue or replace it; the flag gate means only the first missing-flag
     * startup ever calls this per app run anyway, but KEEP makes a concurrent/rapid restart safe
     * too.
     */
    fun scheduleSelectedWorkoutRepair()

    /**
     * Task 4: enqueues the durable, parameter-only Training Readiness projection recompute under
     * the same unique [RESYNC_WORK_NAME] chain as [scheduleResyncWorker], always appended as a
     * durable successor so it never silently drops a rapid repeated request. [config] is the exact
     * requested S/w pair -- only a successful run advances the applied preferences the normal
     * sync/resync paths read.
     */
    fun scheduleTrainingReadinessRecompute(config: TrainingReadinessConfig)
}
