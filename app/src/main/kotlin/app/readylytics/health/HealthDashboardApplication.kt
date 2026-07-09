package app.readylytics.health

import android.app.Application
import android.os.StrictMode
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import app.readylytics.health.BuildConfig
import app.readylytics.health.crashreport.CrashReportHandler
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.di.ApplicationScope
import app.readylytics.health.domain.repository.HealthConnectRepository
import app.readylytics.health.domain.scoring.BackfillHistoricalBaselinesUseCase
import app.readylytics.health.domain.sync.ForegroundSyncController
import app.readylytics.health.domain.util.DomainLogSink
import app.readylytics.health.domain.util.DomainLogger
import app.readylytics.health.domain.util.LogContext
import app.readylytics.health.domain.util.LogLevel
import app.readylytics.health.domain.util.logD
import app.readylytics.health.domain.util.logE
import app.readylytics.health.workers.WorkerScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
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
        Thread.setDefaultUncaughtExceptionHandler(
            CrashReportHandler(applicationContext, Thread.getDefaultUncaughtExceptionHandler()),
        )
        installAndroidLogSink()
        if (BuildConfig.DEBUG) {
            setupPerformanceMonitoring()
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        // Create the resync progress notification channel up front so the foreground worker can post
        // immediately on first use.
        app.readylytics.health.workers.SyncNotifications
            .ensureChannel(this)

        appScope.launch {
            // Run historical baseline backfill once per app start
            runCatching {
                val backfilled = backfillHistoricalBaselines.execute()
                if (backfilled > 0) {
                    logD("HealthDashboardApplication") { "Backfilled $backfilled historical baselines" }
                }
            }.onFailure { e ->
                logE("HealthDashboardApplication", e) { "Historical baseline backfill failed" }
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

    private fun installAndroidLogSink() {
        DomainLogger.installSink(
            object : DomainLogSink {
                override fun log(
                    level: LogLevel,
                    tag: String,
                    message: String,
                    throwable: Throwable?,
                    context: LogContext,
                ) {
                    if (BuildConfig.DEBUG) {
                        val formattedMsg =
                            if (context.sessionId != null) {
                                "[Session:${context.sessionId}] $message"
                            } else {
                                message
                            }
                        when (level) {
                            LogLevel.INFO -> Log.i(tag, formattedMsg)
                            LogLevel.WARN -> Log.w(tag, formattedMsg, throwable)
                            LogLevel.ERROR -> Log.e(tag, formattedMsg, throwable)
                        }
                    }
                }
            },
        )
    }
}
