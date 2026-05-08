package com.gregor.lauritz.healthdashboard

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.domain.repository.HealthConnectRepository
import com.gregor.lauritz.healthdashboard.domain.sync.ForegroundSyncController
import com.gregor.lauritz.healthdashboard.workers.WorkerScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class HealthDashboardApplication :
    Application(),
    LifecycleEventObserver,
    Configuration.Provider {
    @Inject
    lateinit var hcRepository: HealthConnectRepository

    @Inject
    lateinit var foregroundSyncController: ForegroundSyncController

    @Inject
    lateinit var settingsRepo: SettingsRepository

    @Inject
    lateinit var workerScheduler: WorkerScheduler

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() =
            Configuration
                .Builder()
                .setWorkerFactory(workerFactory)
                .build()

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        appScope.launch(Dispatchers.IO) {
            val schedule = settingsRepo.backupSchedule.first()
            workerScheduler.scheduleBackupWorker(schedule)
            workerScheduler.scheduleBirthdayWorker()
            workerScheduler.scheduleDataCleanupWorker()
            workerScheduler.scheduleWidgetUpdateWorker()
        }
    }

    override fun onStateChanged(
        source: LifecycleOwner,
        event: Lifecycle.Event,
    ) {
        // Redundant sync trigger removed. MainActivity handles foreground sync via SyncViewModel.
    }

    override fun onTerminate() {
        super.onTerminate()
        appScope.cancel()
    }
}
