package com.gregor.lauritz.healthdashboard

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.gregor.lauritz.healthdashboard.data.healthconnect.HealthConnectRepository
import com.gregor.lauritz.healthdashboard.data.healthconnect.PermissionStatus
import com.gregor.lauritz.healthdashboard.data.preferences.BackupSchedule
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferencesRepository
import com.gregor.lauritz.healthdashboard.data.preferences.BackupPreferencesRepository
import com.gregor.lauritz.healthdashboard.domain.sync.ForegroundSyncController
import com.gregor.lauritz.healthdashboard.workers.WorkerScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class HealthDashboardApplication :
    Application(),
    LifecycleEventObserver {
    @Inject
    lateinit var hcRepository: HealthConnectRepository

    @Inject
    lateinit var foregroundSyncController: ForegroundSyncController

    @Inject
    lateinit var prefsRepository: UserPreferencesRepository

    @Inject
    lateinit var backupPrefsRepository: BackupPreferencesRepository

    @Inject
    lateinit var workerScheduler: WorkerScheduler

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        appScope.launch(Dispatchers.IO) {
            val schedule = backupPrefsRepository.backupSchedule.first()
            workerScheduler.scheduleBackupWorker(schedule)
        }
    }

    override fun onStateChanged(
        source: LifecycleOwner,
        event: Lifecycle.Event,
    ) {
        if (event == Lifecycle.Event.ON_START) {
            appScope.launch {
                if (hcRepository.checkPermissions() is PermissionStatus.Granted) {
                    foregroundSyncController.evaluateAndSync()
                }
            }
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        appScope.cancel()
    }
}
