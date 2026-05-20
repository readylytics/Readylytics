package com.gregor.lauritz.healthdashboard

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
import com.gregor.lauritz.healthdashboard.data.preferences.AppTheme
import com.gregor.lauritz.healthdashboard.ui.navigation.AppNavHost
import com.gregor.lauritz.healthdashboard.ui.sync.SyncViewModel
import com.gregor.lauritz.healthdashboard.ui.theme.FitDashboardTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
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
