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
import app.readylytics.health.di.ReleaseLogSink
import app.readylytics.health.domain.migration.DatabaseMigrationController
import app.readylytics.health.domain.scoring.BackfillHistoricalBaselinesUseCase
import app.readylytics.health.domain.sync.HealthSyncUseCase
import app.readylytics.health.domain.util.DomainLogSink
import app.readylytics.health.domain.util.DomainLogger
import app.readylytics.health.domain.util.LogContext
import app.readylytics.health.domain.util.LogLevel
import app.readylytics.health.util.SecureFileLogSink
import app.readylytics.health.workers.WorkerScheduler
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class HealthDashboardApplication :
    Application(),
    LifecycleEventObserver,
    Configuration.Provider {
    @Inject
    lateinit var settingsRepo: SettingsRepository

    @Inject
    lateinit var workerScheduler: WorkerScheduler

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var backfillHistoricalBaselines: Lazy<BackfillHistoricalBaselinesUseCase>

    @Inject
    lateinit var healthSyncUseCase: Lazy<HealthSyncUseCase>

    @Inject
    lateinit var databaseMigrationController: DatabaseMigrationController

    @Inject
    @ApplicationScope
    lateinit var appScope: CoroutineScope

    @Inject
    @ReleaseLogSink
    lateinit var secureLogSink: SecureFileLogSink

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

        val startupInitializer =
            DatabaseReadyStartupInitializer(
                healthSyncUseCase = healthSyncUseCase,
                backfillHistoricalBaselines = backfillHistoricalBaselines,
                settingsRepository = settingsRepo,
                workerScheduler = workerScheduler,
            )
        appScope.launch {
            databaseMigrationController.state.collect { state ->
                startupInitializer.initializeIfReady(state.readiness)
            }
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
        if (BuildConfig.DEBUG) {
            // Standard debug logging directly to logcat
            DomainLogger.installSink(
                object : DomainLogSink {
                    override fun log(
                        level: LogLevel,
                        tag: String,
                        message: String,
                        throwable: Throwable?,
                        context: LogContext,
                    ) {
                        val formatted = "[Session:${context.sessionId ?: "none"}] $message"
                        when (level) {
                            LogLevel.INFO -> Log.d(tag, formatted)
                            LogLevel.WARN -> Log.w(tag, formatted, throwable)
                            LogLevel.ERROR -> Log.e(tag, formatted, throwable)
                        }
                    }
                },
            )
        } else {
            // Release build secure log file sink (includes sanitized logcat mirroring)
            DomainLogger.installSink(secureLogSink)
        }
    }
}
