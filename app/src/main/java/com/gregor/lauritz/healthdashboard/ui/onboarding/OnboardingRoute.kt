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
import com.gregor.lauritz.healthdashboard.ui.sync.SyncUiState
import com.gregor.lauritz.healthdashboard.ui.sync.SyncViewModel
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun OnboardingRoute(
    syncViewModel: SyncViewModel,
    onboardingViewModel: OnboardingViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uiState by syncViewModel.uiState.collectAsStateWithLifecycle()
    val permissions = remember { syncViewModel.requiredPermissions }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = PermissionController.createRequestPermissionResultContract(),
        ) { granted ->
            if (granted.containsAll(permissions)) {
                syncViewModel.onPermissionsGranted()
            }
        }

    OnboardingScreen(
        onGrantPermissionsClick = { day, month, year, gender, physiologyProfile, dynamicColorEnabled ->
            onboardingViewModel.saveProfile(
                day = day,
                month = month,
                year = year,
                gender = gender,
                physiologyProfile = physiologyProfile,
                dynamicColorEnabled = dynamicColorEnabled,
                onComplete = {
                    permissionLauncher.launch(permissions)
                }
            )
        },
        onOpenSettingsClick = {
            val intent = Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
            runCatching { context.startActivity(intent) }
        },
        isLoading = uiState is SyncUiState.SyncingCatchUp,
    )
}
