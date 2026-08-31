package app.readylytics.health.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.readylytics.health.core.database.data.local.DataRollupManager
import app.readylytics.health.core.model.domain.sync.ScoreInvalidation
import app.readylytics.health.core.model.domain.util.RetentionBounds
import app.readylytics.health.core.model.domain.util.logE
import app.readylytics.health.core.model.workers.WorkerScheduler
import dagger.Lazy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.time.LocalDate

/**
 * Periodic hot→warm rollup: folds raw heart-rate samples older than the fixed 90-day hot tier into
 * 1-minute `hr_minute_buckets` and deletes the raw rows. Durable via WorkManager; a whole-pass
 * failure returns `Result.retry()` (WorkManager EXPONENTIAL backoff).
 *
 * R2-CACHE-001: when the rollup actually touched data, it enqueues a bounded recompute-only resync
 * (`WorkerScheduler.scheduleResyncWorker(recomputeOnly = true, startDate, endDate)`) over
 * `ScoreInvalidation.affectedRange` of the touched range -- the raw data `daily_summaries` was
 * derived from just changed underneath it. A no-op rollup (nothing to touch) enqueues nothing.
 */
@HiltWorker
class DataRollupWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val rollupManager: Lazy<DataRollupManager>,
        private val workerScheduler: Lazy<WorkerScheduler>,
        private val clock: Clock,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result =
            try {
                val touched =
                    rollupManager.get().rollupExpiredHotTier(RetentionBounds.resolveHotTierCutoffMs(clock.instant()))
                if (touched != null) {
                    val affected = ScoreInvalidation.affectedRange(touched, LocalDate.now(clock))
                    workerScheduler.get().scheduleResyncWorker(
                        recomputeOnly = true,
                        startDate = affected.start,
                        endDate = affected.endInclusive,
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
