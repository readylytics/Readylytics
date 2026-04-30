package com.gregor.lauritz.healthdashboard

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.gregor.lauritz.healthdashboard.data.healthconnect.HealthConnectRepository
import com.gregor.lauritz.healthdashboard.data.preferences.AppTheme
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferencesRepository
import com.gregor.lauritz.healthdashboard.ui.navigation.AppNavHost
import com.gregor.lauritz.healthdashboard.ui.sync.SyncEvent
import com.gregor.lauritz.healthdashboard.ui.sync.SyncViewModel
import com.gregor.lauritz.healthdashboard.ui.theme.FitDashboardTheme
import com.gregor.lauritz.healthdashboard.workers.WorkerScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var hcRepo: HealthConnectRepository

    @Inject
    lateinit var prefsRepo: UserPreferencesRepository

    @Inject
    lateinit var workerScheduler: WorkerScheduler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val prefs by prefsRepo.userPreferences.collectAsState(initial = null)
            val appTheme = prefs?.appTheme ?: AppTheme.SYSTEM

            FitDashboardTheme(appTheme = appTheme) {
                val viewModel: SyncViewModel = hiltViewModel()
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

                // Trigger permission check every time the activity comes to the foreground,
                // not just on first composition — handles the case where the user grants
                // permissions in HC settings and then returns to the app.
                LaunchedEffect(Unit) {
                    repeatOnLifecycle(Lifecycle.State.STARTED) {
                        viewModel.onAppForeground()
                    }
                }

                AppNavHost(
                    viewModel = viewModel,
                    hcRepo = hcRepo,
                    prefsRepo = prefsRepo
                )
            }
        }
    }
}
