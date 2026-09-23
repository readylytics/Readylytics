package app.readylytics.health.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.readylytics.health.core.database.data.local.DataRollupManager
import app.readylytics.health.core.database.data.local.RoomDirtyRangeStore
import app.readylytics.health.core.model.domain.sync.ScoringRunContext
import app.readylytics.health.core.model.domain.util.RetentionBounds
import app.readylytics.health.core.model.domain.util.logE
import app.readylytics.health.core.model.workers.WorkerScheduler
import app.readylytics.health.data.preferences.SettingsRepository
import dagger.Lazy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.Clock

/**
 * Periodic hot→warm rollup: folds raw heart-rate samples older than the fixed 90-day hot tier into
 * 1-minute `hr_minute_buckets` and deletes the raw rows. Durable via WorkManager; a whole-pass
 * failure returns `Result.retry()` (WorkManager EXPONENTIAL backoff).
 *
 * R2-CACHE-001: when the rollup actually touched data, it enqueues a bounded recompute-only resync
 * (`WorkerScheduler.scheduleResyncWorker(recomputeOnly = true, startDate, endDate)`) over
 * pending dirty ranges.
 */
@HiltWorker
class DataRollupWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val rollupManager: Lazy<DataRollupManager>,
        private val workerScheduler: Lazy<WorkerScheduler>,
        private val dirtyRangeStore: RoomDirtyRangeStore,
        private val settingsRepo: SettingsRepository,
        private val clock: Clock,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result =
            try {
                val prefs = settingsRepo.userPreferences.first()
                val runContext = ScoringRunContext.capture(prefs, clock.instant())
                rollupManager.get().rollupExpiredHotTier(
                    runContext,
                    RetentionBounds.resolveHotTierCutoffMs(runContext.instant),
                )

                val pending = dirtyRangeStore.pending(100)
                if (pending.isNotEmpty()) {
                    workerScheduler.get().scheduleResyncWorker(
                        recomputeOnly = true,
                        startDate = pending.minOf { it.nextDay },
                        endDate = pending.maxOf { it.endInclusive },
                    )
                }
                Result.success()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logE("DataRollupWorker", e) { "Data rollup failed" }
                Result.retry()
            }
    }
