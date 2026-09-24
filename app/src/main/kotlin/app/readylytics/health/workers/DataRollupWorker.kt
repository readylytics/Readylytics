package app.readylytics.health.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.readylytics.health.core.database.data.local.DataRollupManager
import app.readylytics.health.core.model.domain.util.RetentionBounds
import app.readylytics.health.core.model.domain.util.logE
import app.readylytics.health.core.model.domain.util.logI
import dagger.Lazy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.time.Clock

/**
 * Periodic hot→warm rollup: folds raw heart-rate samples older than the fixed 90-day hot tier into
 * 1-minute `hr_minute_buckets` and deletes the raw rows. Durable via WorkManager; a whole-pass
 * failure returns `Result.retry()` (WorkManager EXPONENTIAL backoff).
 *
 * Aging data into the warm tier never invalidates retained `daily_summaries`: every retained day
 * was already scored (and its baselines frozen) from the full-resolution raw samples, so
 * recomputing it from the warm-tier approximation would only make it less accurate -- and doing
 * so nightly re-ran an ~84-day recompute every time the 90-day boundary advanced by one day.
 */
@HiltWorker
class DataRollupWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val rollupManager: Lazy<DataRollupManager>,
        private val clock: Clock,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result =
            try {
                val touched =
                    rollupManager.get().rollupExpiredHotTier(RetentionBounds.resolveHotTierCutoffMs(clock.instant()))
                if (touched != null) {
                    logI(TAG) { "Rolled up ${touched.start}..${touched.endInclusive} into the warm tier" }
                }
                Result.success()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logE(TAG, e) { "Data rollup failed" }
                Result.retry()
            }

        private companion object {
            const val TAG = "DataRollupWorker"
        }
    }
