package app.readylytics.health.domain.sync

import app.readylytics.health.workers.WorkerScheduler
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HealthDataRefreshAdapter
    @Inject
    constructor(
        private val healthSyncUseCase: HealthSyncUseCase,
        private val workerScheduler: WorkerScheduler,
    ) : HealthDataRefresh {
        override suspend fun refreshAffectedWindow() {
            healthSyncUseCase.sync(windowDays = SETTINGS_REFRESH_WINDOW_DAYS)
        }

        override suspend fun refreshHistorical() {
            workerScheduler.scheduleResyncWorker(recomputeOnly = true)
        }
    }
