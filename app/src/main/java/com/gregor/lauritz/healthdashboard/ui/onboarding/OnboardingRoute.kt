package com.gregor.lauritz.healthdashboard.ui.onboarding

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gregor.lauritz.healthdashboard.data.healthconnect.HealthConnectRepository
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferencesRepository
import com.gregor.lauritz.healthdashboard.ui.sync.SyncUiState
import com.gregor.lauritz.healthdashboard.ui.sync.SyncViewModel
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

@Composable
fun OnboardingRoute(
    viewModel: SyncViewModel,
    hcRepo: HealthConnectRepository,
    prefsRepo: UserPreferencesRepository, // Added prefsRepo
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val permissions = remember { hcRepo.requiredPermissions }
    val scope = rememberCoroutineScope()

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = PermissionController.createRequestPermissionResultContract(),
        ) { granted ->
            if (granted.containsAll(permissions)) {
                viewModel.onPermissionsGranted()
            }
        }

    OnboardingScreen(
        onGrantPermissionsClick = { day, month, year, gender, physiologyProfile ->
            scope.launch {
                prefsRepo.updateBirthday(day, month, year)
                prefsRepo.updateGender(gender)
                prefsRepo.updatePhysiologyProfile(physiologyProfile)

                // Trigger permission request after saving profile
                permissionLauncher.launch(permissions)
            }
        },
        onOpenSettingsClick = {
            val intent = Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
            runCatching { context.startActivity(intent) }
        },
        isLoading = uiState is SyncUiState.SyncingCatchUp,
    )
}
