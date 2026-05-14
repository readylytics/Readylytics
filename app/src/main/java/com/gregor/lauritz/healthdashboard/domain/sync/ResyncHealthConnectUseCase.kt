package com.gregor.lauritz.healthdashboard.domain.sync

import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HeartRateDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HrvDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.data.local.dao.WorkoutDao
import com.gregor.lauritz.healthdashboard.data.sync.HealthSyncUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ResyncHealthConnectUseCase
    @Inject
    constructor(
        private val sleepDao: SleepSessionDao,
        private val heartRateDao: HeartRateDao,
        private val hrvDao: HrvDao,
        private val workoutDao: WorkoutDao,
        private val dailySummaryDao: DailySummaryDao,
        private val healthSyncUseCase: HealthSyncUseCase,
    ) {
        suspend fun execute(): Result<Unit> =
            withContext(Dispatchers.Default) {
                runCatching {
                    // Clear all HC-sourced data.
                    sleepDao.deleteAll()
                    heartRateDao.deleteAll()
                    hrvDao.deleteAll()
                    workoutDao.deleteAll()
                    dailySummaryDao.deleteAll()

                    // Re-sync last 60 days from Health Connect.
                    healthSyncUseCase.sync(windowDays = 60).getOrThrow()
                }
            }
    }
