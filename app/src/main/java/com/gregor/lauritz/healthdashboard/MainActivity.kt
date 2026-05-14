package com.gregor.lauritz.healthdashboard

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gregor.lauritz.healthdashboard.data.preferences.AppTheme
import com.gregor.lauritz.healthdashboard.ui.navigation.AppNavHost
import com.gregor.lauritz.healthdashboard.ui.sync.SyncEvent
import com.gregor.lauritz.healthdashboard.ui.sync.SyncViewModel
import com.gregor.lauritz.healthdashboard.ui.theme.FitDashboardTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val viewModel: SyncViewModel = hiltViewModel()
            val prefs by viewModel.userPreferences.collectAsState(initial = null)

            // Keep splash screen on until preferences are loaded to prevent theme flash
            splashScreen.setKeepOnScreenCondition { prefs == null }

            val appTheme = prefs?.appTheme ?: AppTheme.SYSTEM

            FitDashboardTheme(
                appTheme = appTheme,
            ) {
                val context = LocalContext.current

                // Handle sync events (e.g., showing a Toast)
                LaunchedEffect(Unit) {
                    viewModel.syncEvents.collectLatest { event ->
                        when (event) {
                            SyncEvent.SyncCompleted -> {
                                Toast
                                    .makeText(
                                        context,
                                        R.string.sync_completed,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                            }
                        }
                    }
                }

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
