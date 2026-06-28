package app.readylytics.health.domain.sync

import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.readylytics.health.workers.HealthResyncWorker
import app.readylytics.health.workers.WorkerScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HistoricalResyncControllerImpl
    @Inject
    constructor(
        private val workerScheduler: WorkerScheduler,
        workManager: WorkManager,
    ) : HistoricalResyncController {
        override val state: Flow<HistoricalResyncState> =
            workManager
                .getWorkInfosForUniqueWorkFlow(WorkerScheduler.RESYNC_WORK_NAME)
                .map { workInfos ->
                    val info = workInfos.firstOrNull()
                    val running =
                        info?.state == WorkInfo.State.RUNNING || info?.state == WorkInfo.State.ENQUEUED
                    HistoricalResyncState(
                        running = running,
                        current = info?.progress?.getInt(HealthResyncWorker.KEY_CURRENT, 0) ?: 0,
                        total = info?.progress?.getInt(HealthResyncWorker.KEY_TOTAL, 0) ?: 0,
                    )
                }

        override suspend fun requestHistoricalResync() {
            workerScheduler.scheduleResyncWorker()
        }

        override fun schedulePeriodicSync(intervalMinutes: Long) {
            workerScheduler.schedulePeriodicSync(intervalMinutes)
        }

        override fun cancelPeriodicSync() {
            workerScheduler.cancelPeriodicSync()
        }
    }
