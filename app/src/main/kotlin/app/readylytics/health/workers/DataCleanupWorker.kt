package app.readylytics.health.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.readylytics.health.core.database.data.local.RetentionCleanup
import app.readylytics.health.core.database.data.local.RoomDirtyRangeStore
import app.readylytics.health.core.model.domain.migration.DatabaseReadiness
import app.readylytics.health.core.model.domain.migration.DatabaseReadinessInspector
import app.readylytics.health.core.model.domain.sync.ScoringRunContext
import app.readylytics.health.core.model.domain.util.logE
import app.readylytics.health.core.model.workers.WorkerScheduler
import app.readylytics.health.data.preferences.SettingsRepository
import dagger.Lazy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.Clock

@HiltWorker
class DataCleanupWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val retentionCleanup: Lazy<RetentionCleanup>,
        private val dirtyRangeStore: RoomDirtyRangeStore,
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
                val runContext = ScoringRunContext.capture(prefs, clock.instant())
                if (!prefs.retentionDaysEnabled) return Result.success()

                cleanup.deleteBefore(runContext.retentionStartMs, runContext)

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
                logE("DataCleanupWorker", e) { "Data cleanup failed" }
                Result.failure()
            }
        }
    }
