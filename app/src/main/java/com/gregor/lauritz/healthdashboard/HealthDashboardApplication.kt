package com.gregor.lauritz.healthdashboard

import android.app.Application
import android.os.StrictMode
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import com.gregor.lauritz.healthdashboard.BuildConfig
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.di.ApplicationScope
import com.gregor.lauritz.healthdashboard.domain.repository.HealthConnectRepository
import com.gregor.lauritz.healthdashboard.domain.scoring.BackfillHistoricalBaselinesUseCase
import com.gregor.lauritz.healthdashboard.domain.sync.ForegroundSyncController
import com.gregor.lauritz.healthdashboard.domain.util.logD
import com.gregor.lauritz.healthdashboard.workers.WorkerScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    @Inject
    lateinit var backfillHistoricalBaselines: BackfillHistoricalBaselinesUseCase

    @Inject
    @ApplicationScope
    lateinit var appScope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() =
            Configuration
                .Builder()
                .setWorkerFactory(workerFactory)
                .build()

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            setupPerformanceMonitoring()
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        // Create the resync progress notification channel up front so the foreground worker can post
        // immediately on first use.
        com.gregor.lauritz.healthdashboard.workers.SyncNotifications
            .ensureChannel(this)

        appScope.launch(Dispatchers.IO) {
            // Run historical baseline backfill once per app start
            runCatching {
                val backfilled = backfillHistoricalBaselines.execute()
                if (backfilled > 0) {
                    logD("HealthDashboardApplication") { "Backfilled $backfilled historical baselines" }
                }
            }.onFailure { e ->
                logD("HealthDashboardApplication") { "Backfill failed: ${e.message}" }
            }

            val schedule = settingsRepo.backupSchedule.first()
            workerScheduler.scheduleBackupWorker(schedule)
            workerScheduler.scheduleBirthdayWorker()
            workerScheduler.scheduleDataCleanupWorker()
        }
    }

    override fun onStateChanged(
        source: LifecycleOwner,
        event: Lifecycle.Event,
    ) {
        // Redundant sync trigger removed. MainActivity handles foreground sync via SyncViewModel.
    }

    private fun setupPerformanceMonitoring() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy
                .Builder()
                .detectAll()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy
                .Builder()
                .detectLeakedSqlLiteObjects()
                .detectLeakedClosableObjects()
                .penaltyLog()
                .build(),
        )
    }
}
