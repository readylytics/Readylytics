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
import app.readylytics.health.benchmark.BenchmarkDataSeeder
import app.readylytics.health.core.database.data.security.SqlCipherKeyManager
import app.readylytics.health.core.healthconnect.domain.sync.HealthSyncUseCase
import app.readylytics.health.core.model.di.ApplicationScope
import app.readylytics.health.core.model.domain.migration.DatabaseReadiness
import app.readylytics.health.core.model.domain.util.DomainLogSink
import app.readylytics.health.core.model.domain.util.DomainLogger
import app.readylytics.health.core.model.domain.util.LogContext
import app.readylytics.health.core.model.domain.util.LogLevel
import app.readylytics.health.core.model.domain.util.logD
import app.readylytics.health.core.model.domain.util.logE
import app.readylytics.health.core.model.workers.WorkerScheduler
import app.readylytics.health.core.scoring.domain.scoring.BackfillHistoricalBaselinesUseCase
import app.readylytics.health.crashreport.CrashReportHandler
import app.readylytics.health.data.preferences.PhysiologyPreferences
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.di.ReleaseLogSink
import app.readylytics.health.domain.migration.DatabaseMigrationController
import app.readylytics.health.util.SecureFileLogSink
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CancellationException
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
    lateinit var settingsRepo: Lazy<SettingsRepository>

    @Inject
    lateinit var workerScheduler: WorkerScheduler

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var backfillHistoricalBaselines: Lazy<BackfillHistoricalBaselinesUseCase>

    @Inject
    internal lateinit var physiologyPreferences: Lazy<PhysiologyPreferences>

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

    @Inject
    lateinit var sqlCipherKeyManager: Lazy<SqlCipherKeyManager>

    override fun onCreate() {
        super.onCreate()
        val crashReportHandler = CrashReportHandler(applicationContext, Thread.getDefaultUncaughtExceptionHandler())
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (sqlCipherKeyManager.get().isKeyCorrupted.value) {
                // If the key is corrupted, Room's DB open fails and propagates an exception.
                // Since this exception is generally uncaught in ViewModels, we catch it globally.
                // Restarting the activity ensures the UI renders the recovery screen (via the
                // isKeyCorrupted observer) instead of crashing the process.
                val intent = packageManager.getLaunchIntentForPackage(packageName)
                if (intent != null) {
                    intent.addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK,
                    )
                    startActivity(intent)
                }
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(0)
            } else {
                crashReportHandler.uncaughtException(thread, throwable)
            }
        }
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
                physiologyPreferences = physiologyPreferences,
                workerScheduler = workerScheduler,
            )
        val startupCoordinator = DatabaseReadyStartupCoordinator(startupInitializer)
        val preferencesPrewarmer = PreferencesPrewarmer(settingsRepo)
        appScope.launch { preferencesPrewarmer.prewarm() }
        appScope.launch {
            startupCoordinator.observe(databaseMigrationController.state)
        }
        appScope.launch {
            // Wait for the same DB-migration readiness gate the startup coordinator above
            // observes -- seeding before the DB is ready would race the migration and
            // silently no-op (BenchmarkDataSeeder no-ops outside the "benchmark" build type
            // anyway, but on that build type this ordering matters).
            databaseMigrationController.state.first { it.readiness == DatabaseReadiness.Ready }
            try {
                BenchmarkDataSeeder.seedIfNeeded(this@HealthDashboardApplication)
                logD(BENCHMARK_SEED_LOG_TAG) { "Benchmark data seeding completed" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logE(BENCHMARK_SEED_LOG_TAG, e) { "Benchmark data seeding failed" }
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
                            LogLevel.DEBUG -> Log.d(tag, formatted)
                            LogLevel.INFO -> Log.i(tag, formatted)
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

    private companion object {
        const val BENCHMARK_SEED_LOG_TAG = "BenchmarkDataSeeder"
    }
}
