package com.gregor.lauritz.healthdashboard.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HeartRateDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HrvDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.data.local.dao.WorkoutDao
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferencesRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId

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
        private val prefsRepo: UserPreferencesRepository,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            return runCatching {
                val prefs = prefsRepo.userPreferences.first()
                if (!prefs.retentionDaysEnabled) {
                    return Result.success()
                }

                val retentionDays = prefs.retentionDays
                val zoneId = ZoneId.systemDefault()
                val cutoffDate = LocalDate.now(zoneId).minusDays(retentionDays.toLong())
                val cutoffMs = cutoffDate.atStartOfDay(zoneId).toInstant().toEpochMilli()

                sleepDao.deleteBeforeTimestamp(cutoffMs)
                heartRateDao.deleteBeforeTimestamp(cutoffMs)
                hrvDao.deleteBeforeTimestamp(cutoffMs)
                workoutDao.deleteBeforeTimestamp(cutoffMs)
                dailySummaryDao.deleteBeforeTimestamp(cutoffMs)

                Result.success()
            }.getOrElse {
                Result.failure()
            }
        }
    }
