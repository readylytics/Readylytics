package app.readylytics.health.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.readylytics.health.core.database.data.local.RetentionCleanup
import app.readylytics.health.core.model.domain.migration.DatabaseReadiness
import app.readylytics.health.core.model.domain.migration.DatabaseReadinessInspector
import app.readylytics.health.core.model.domain.sync.ScoringRunContext
import app.readylytics.health.core.model.domain.util.logE
import app.readylytics.health.core.model.domain.util.logI
import app.readylytics.health.data.preferences.SettingsRepository
import dagger.Lazy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.Clock

/**
 * Daily retention enforcement via [RetentionCleanup.deleteBefore]. Deleting data that aged out of
 * the retention window never invalidates retained `daily_summaries`: each retained day was scored
 * (and its baselines frozen) while the older data still existed, so recomputing it now would only
 * drop inputs -- and doing so nightly re-ran an ~84-day recompute every time the cutoff advanced.
 */
@HiltWorker
class DataCleanupWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val retentionCleanup: Lazy<RetentionCleanup>,
        private val settingsRepo: SettingsRepository,
        private val databaseReadinessGate: DatabaseReadinessInspector,
        private val clock: Clock,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            if (databaseReadinessGate.inspect() != DatabaseReadiness.Ready) {
                return Result.retry()
            }
            val cleanup = retentionCleanup.get()
            return try {
                val prefs = settingsRepo.userPreferences.first()
                val runContext = ScoringRunContext.capture(prefs, clock.instant())
                if (!prefs.retentionDaysEnabled) return Result.success()

                val touched = cleanup.deleteBefore(runContext.retentionStartMs, runContext)
                if (touched != null) {
                    logI(TAG) { "Deleted retention-expired data ${touched.start}..${touched.endInclusive}" }
                }

                Result.success()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logE(TAG, e) { "Data cleanup failed" }
                Result.failure()
            }
        }

        private companion object {
            const val TAG = "DataCleanupWorker"
        }
    }
