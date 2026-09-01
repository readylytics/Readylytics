package app.readylytics.health

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.readylytics.health.benchmark.applyBenchmarkTestTagSemantics
import app.readylytics.health.core.database.data.security.SqlCipherKeyManager
import app.readylytics.health.core.model.data.preferences.AppTheme
import app.readylytics.health.core.model.domain.backup.RestoreResult
import app.readylytics.health.core.model.domain.migration.DatabaseReadiness
import app.readylytics.health.core.model.domain.util.logE
import app.readylytics.health.crashreport.DiagnosticLogFileExport
import app.readylytics.health.crashreport.buildLogFileShareIntent
import app.readylytics.health.data.backup.LocalRestoreManager
import app.readylytics.health.di.ReleaseLogSink
import app.readylytics.health.domain.migration.DatabaseMigrationController
import app.readylytics.health.ui.crashreport.CrashReportPrompt
import app.readylytics.health.ui.migration.DatabaseMigrationScreen
import app.readylytics.health.ui.navigation.AppNavHost
import app.readylytics.health.ui.recovery.DatabaseRecoveryScreen
import app.readylytics.health.ui.sync.SyncViewModel
import app.readylytics.health.ui.theme.DatabaseReadinessTheme
import app.readylytics.health.ui.theme.FitDashboardTheme
import app.readylytics.health.util.SecureFileLogSink
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private companion object {
        // Upper bound on how long the splash screen may block the first frame while
        // waiting for user preferences. Prevents an indefinite splash (and Espresso
        // idle deadlock) if the DataStore read stalls.
        const val SPLASH_MAX_WAIT_MS = 2000L
    }

    @Inject
    lateinit var sqlCipherKeyManager: SqlCipherKeyManager

    @Inject
    lateinit var localRestoreManager: Lazy<LocalRestoreManager>

    @Inject
    lateinit var databaseMigrationController: DatabaseMigrationController

    @Inject
    @ReleaseLogSink
    lateinit var secureLogSink: SecureFileLogSink

    private var isKeyValidationComplete by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { sqlCipherKeyManager.validateKeyDecryption() }
            DiagnosticLogFileExport.pruneDiagnosticCache(cacheDir)
            isKeyValidationComplete = true
        }

        setContent {
            if (!isKeyValidationComplete) return@setContent

            // Applied once at the composition root: no-op outside the "benchmark" build type,
            // and required inside it so UiAutomator's By.res(...) selectors can find
            // Modifier.testTag-ed Compose nodes (see BenchmarkSemantics.kt).
            Box(modifier = Modifier.applyBenchmarkTestTagSemantics()) {
                val migrationState by databaseMigrationController.state.collectAsStateWithLifecycle()
                when (val readiness = migrationState.readiness) {
                    DatabaseReadiness.Ready -> ReadylyticsContent(splashScreen)
                    DatabaseReadiness.KeyCorrupted -> {
                        splashScreen.setKeepOnScreenCondition { false }
                        val dbFile = remember { getDatabasePath("health_dashboard.db") }
                        DatabaseReadinessTheme {
                            DatabaseRecoveryScreen(
                                onResetDatabase = {
                                    sqlCipherKeyManager.resetKeyAndDatabase(dbFile)
                                    recreate()
                                },
                                onRestoreBackup = { uri, onResult ->
                                    lifecycleScope.launch {
                                        val result = localRestoreManager.get().applyRestore(uri)
                                        if (result is RestoreResult.Success ||
                                            result is RestoreResult.SuccessRequiresRestart
                                        ) {
                                            onResult(true, null)
                                        } else if (result is RestoreResult.Failure) {
                                            onResult(false, getString(R.string.recovery_error_default))
                                        }
                                    }
                                },
                            )
                        }
                    }
                    is DatabaseReadiness.MigrationRequired -> {
                        LaunchedEffect(readiness) {
                            databaseMigrationController.startOrResume()
                        }
                        DatabaseReadinessTheme {
                            DatabaseMigrationScreen(
                                readiness = readiness,
                                progress = migrationState.progress,
                                onRetry = databaseMigrationController::startOrResume,
                                onSendDiagnostics = ::sendMigrationDiagnostics,
                            )
                        }
                    }
                    is DatabaseReadiness.InsufficientSpace,
                    is DatabaseReadiness.Failed,
                    -> {
                        DatabaseReadinessTheme {
                            DatabaseMigrationScreen(
                                readiness = readiness,
                                progress = migrationState.progress,
                                onRetry = databaseMigrationController::startOrResume,
                                onSendDiagnostics = ::sendMigrationDiagnostics,
                            )
                        }
                    }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun ReadylyticsContent(splashScreen: androidx.core.splashscreen.SplashScreen) {
        val dbFile = remember { getDatabasePath("health_dashboard.db") }
        val isDatabaseCorrupted by sqlCipherKeyManager.isKeyCorrupted.collectAsStateWithLifecycle()

        if (isDatabaseCorrupted) {
            splashScreen.setKeepOnScreenCondition { false }
            FitDashboardTheme {
                DatabaseRecoveryScreen(
                    onResetDatabase = {
                        sqlCipherKeyManager.resetKeyAndDatabase(dbFile)
                        recreate()
                    },
                    onRestoreBackup = { uri, onResult ->
                        lifecycleScope.launch {
                            val result = localRestoreManager.get().applyRestore(uri)
                            if (result is RestoreResult.Success ||
                                result is RestoreResult.SuccessRequiresRestart
                            ) {
                                onResult(true, null)
                            } else if (result is RestoreResult.Failure) {
                                onResult(false, getString(R.string.recovery_error_default))
                            }
                        }
                    },
                )
            }
        } else {
            val viewModel: SyncViewModel = hiltViewModel()
            val prefs by viewModel.userPreferences.collectAsStateWithLifecycle(initialValue = null)

            // Keep splash screen on until preferences are loaded to prevent theme
            // flash, but bound the wait so a stalled/slow DataStore read can never
            // trap the app (or an instrumented test) on the splash indefinitely.
            // While the keep-condition returns true the first frame is withheld,
            // which keeps the main looper busy and blocks Espresso idle sync.
            val splashStartMillis = remember { android.os.SystemClock.elapsedRealtime() }
            splashScreen.setKeepOnScreenCondition {
                (!isKeyValidationComplete || prefs == null) &&
                    android.os.SystemClock.elapsedRealtime() - splashStartMillis < SPLASH_MAX_WAIT_MS
            }

            val appTheme = prefs?.appTheme ?: AppTheme.SYSTEM

            FitDashboardTheme(
                appTheme = appTheme,
            ) {
                // Trigger permission check every time the activity comes to the foreground
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer =
                        LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_RESUME) {
                                viewModel.onAppForeground()
                            }
                        }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }

                AppNavHost(viewModel = viewModel)
                CrashReportPrompt()
            }
        }
    }

    private fun sendMigrationDiagnostics() {
        lifecycleScope.launch {
            runCatching {
                val logText = withContext(Dispatchers.IO) { secureLogSink.readLogsDecrypted() }
                val file =
                    withContext(Dispatchers.IO) {
                        DiagnosticLogFileExport.write(cacheDir, logText)
                    }
                startActivity(buildLogFileShareIntent(this@MainActivity, file))
            }.onFailure { throwable ->
                if (throwable is CancellationException) throw throwable
                logE("DatabaseMigration", throwable) { "Failed to prepare diagnostic log" }
                Toast
                    .makeText(
                        this@MainActivity,
                        R.string.database_migration_diagnostics_error,
                        Toast.LENGTH_LONG,
                    ).show()
            }
        }
    }
}
