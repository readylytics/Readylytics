package app.readylytics.health.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.readylytics.health.data.local.dao.DailySummaryDao
import app.readylytics.health.data.local.dao.HeartRateDao
import app.readylytics.health.data.local.dao.HrvDao
import app.readylytics.health.data.local.dao.SleepSessionDao
import app.readylytics.health.data.local.dao.WorkoutDao
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.domain.util.RetentionBounds
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

@HiltWorker
class DataCleanupWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val sleepDao: SleepSessionDao,
        private val heartRateDao: HeartRateDao,
        private val hrvDao: HrvDao,
        private val workoutDao: WorkoutDao,
        private val dailySummaryDao: DailySummaryDao,
        private val settingsRepo: SettingsRepository,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            return try {
                val prefs = settingsRepo.userPreferences.first()
                // Null cutoff means retention is disabled ("unlimited") — keep everything.
                val cutoffMs = RetentionBounds.resolveRetentionCutoffMs(prefs) ?: return Result.success()

                sleepDao.deleteBeforeTimestamp(cutoffMs)
                heartRateDao.deleteBeforeTimestamp(cutoffMs)
                hrvDao.deleteBeforeTimestamp(cutoffMs)
                workoutDao.deleteBeforeTimestamp(cutoffMs)
                dailySummaryDao.deleteBeforeTimestamp(cutoffMs)

                Result.success()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure()
            }
        }
    }
