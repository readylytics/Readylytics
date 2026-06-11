package app.readylytics.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.readylytics.health.data.backup.LocalRestoreManager
import app.readylytics.health.data.preferences.AppTheme
import app.readylytics.health.data.security.SqlCipherKeyManager
import app.readylytics.health.ui.navigation.AppNavHost
import app.readylytics.health.ui.recovery.DatabaseRecoveryScreen
import app.readylytics.health.ui.sync.SyncViewModel
import app.readylytics.health.ui.theme.FitDashboardTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var sqlCipherKeyManager: SqlCipherKeyManager

    @Inject
    lateinit var localRestoreManager: LocalRestoreManager

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val dbFile = getDatabasePath("health_dashboard.db")
        var isDatabaseCorrupted = false
        try {
            sqlCipherKeyManager.validateKeyDecryption()
        } catch (e: Exception) {
            isDatabaseCorrupted = true
        }

        setContent {
            if (isDatabaseCorrupted) {
                FitDashboardTheme {
                    DatabaseRecoveryScreen(
                        onResetDatabase = {
                            sqlCipherKeyManager.resetKeyAndDatabase(dbFile)
                            recreate()
                        },
                        onRestoreBackup = { uri, onResult ->
                            lifecycleScope.launch {
                                val result = localRestoreManager.applyRestore(uri)
                                if (result is LocalRestoreManager.RestoreResult.Success ||
                                    result is LocalRestoreManager.RestoreResult.SuccessRequiresRestart
                                ) {
                                    onResult(true, null)
                                } else if (result is LocalRestoreManager.RestoreResult.Failure) {
                                    onResult(false, result.cause.message)
                                }
                            }
                        },
                    )
                }
            } else {
                val viewModel: SyncViewModel = hiltViewModel()
                val prefs by viewModel.userPreferences.collectAsStateWithLifecycle(initialValue = null)

                // Keep splash screen on until preferences are loaded to prevent theme flash
                splashScreen.setKeepOnScreenCondition { prefs == null }

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
                }
            }
        }
    }
}
