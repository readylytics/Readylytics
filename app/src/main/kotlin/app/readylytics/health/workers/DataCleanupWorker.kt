package app.readylytics.health.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.readylytics.health.core.database.data.local.RetentionCleanup
import app.readylytics.health.core.model.domain.migration.DatabaseReadiness
import app.readylytics.health.core.model.domain.migration.DatabaseReadinessInspector
import app.readylytics.health.core.model.domain.sync.ScoreInvalidation
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
import java.time.LocalDate

/**
 * R2-CACHE-001: when [RetentionCleanup.deleteBefore] actually deleted data, this enqueues a
 * bounded recompute-only resync (`WorkerScheduler.scheduleResyncWorker(recomputeOnly = true,
 * startDate, endDate)`) over `ScoreInvalidation.affectedRange` of the touched range -- the raw
 * data `daily_summaries` was derived from just changed underneath it. A no-op cleanup enqueues
 * nothing.
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
        private val workerScheduler: Lazy<WorkerScheduler>,
        private val clock: Clock,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            if (databaseReadinessGate.inspect() != DatabaseReadiness.Ready) {
                return Result.retry()
            }
            val cleanup = retentionCleanup.get()
            return try {
                val prefs = settingsRepo.userPreferences.first()
                // Null cutoff means retention is disabled ("unlimited") — keep everything.
                val cutoffMs =
                    RetentionBounds.resolveRetentionCutoffMs(prefs, clock.instant()) ?: return Result.success()

                val touched = cleanup.deleteBefore(cutoffMs)
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
                logE("DataCleanupWorker", e) { "Data cleanup failed" }
                Result.failure()
            }
        }
    }
