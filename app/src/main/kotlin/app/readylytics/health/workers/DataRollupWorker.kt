package app.readylytics.health.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.readylytics.health.core.database.data.local.DataRollupManager
import app.readylytics.health.core.model.domain.util.RetentionBounds
import app.readylytics.health.core.model.domain.util.logE
import dagger.Lazy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * Periodic hot→warm rollup: folds raw heart-rate samples older than the fixed 90-day hot tier into
 * 1-minute `hr_minute_buckets` and deletes the raw rows. Durable via WorkManager; a whole-pass
 * failure returns `Result.retry()` (WorkManager EXPONENTIAL backoff).
 */
@HiltWorker
class DataRollupWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val rollupManager: Lazy<DataRollupManager>,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result =
            try {
                rollupManager.get().rollupExpiredHotTier(RetentionBounds.resolveHotTierCutoffMs())
                Result.success()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logE("DataRollupWorker", e) { "Data rollup failed" }
                Result.retry()
            }
    }
