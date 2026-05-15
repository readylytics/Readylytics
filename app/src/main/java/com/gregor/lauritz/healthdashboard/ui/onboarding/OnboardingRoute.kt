package com.gregor.lauritz.healthdashboard.ui.onboarding

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gregor.lauritz.healthdashboard.ui.sync.SyncUiState
import com.gregor.lauritz.healthdashboard.ui.sync.SyncViewModel

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
            com.gregor.lauritz.healthdashboard.domain.util.logD("OnboardingRoute") {
                "Permission result received. Granted: $granted"
            }
            if (granted.containsAll(permissions)) {
                com.gregor.lauritz.healthdashboard.domain.util.logD(
                    "OnboardingRoute",
                ) { "All requested permissions granted by user" }
                syncViewModel.onPermissionsGranted()
            } else {
                val missing = permissions - granted
                com.gregor.lauritz.healthdashboard.domain.util.logD(
                    "OnboardingRoute",
                ) { "User denied some permissions: $missing" }
                syncViewModel.onPermissionsDenied()
            }
        }

    val currentState = uiState
    when (currentState) {
        is SyncUiState.DeviceSelectionReady -> {
            DeviceSelectionScreen(
                devices = currentState.devices,
                onDeviceSelected = { deviceName ->
                    onboardingViewModel.selectDevice(deviceName) {
                        syncViewModel.onDeviceSelected()
                    }
                },
            )
        }
        else -> {
            OnboardingScreen(
                onGrantPermissionsClick = { day, month, year, gender, physiologyProfile, dynamicColorEnabled ->
                    com.gregor.lauritz.healthdashboard.domain.util.logD("OnboardingRoute") {
                        "Grant Access clicked. Saving profile first..."
                    }
                    onboardingViewModel.saveProfile(
                        day = day,
                        month = month,
                        year = year,
                        gender = gender,
                        physiologyProfile = physiologyProfile,
                        dynamicColorEnabled = dynamicColorEnabled,
                        onComplete = {
                            com.gregor.lauritz.healthdashboard.domain.util.logD("OnboardingRoute") {
                                "Profile saved. Launching HC permissions: $permissions"
                            }
                            permissionLauncher.launch(permissions)
                        },
                    )
                },
                onOpenSettingsClick = {
                    val intent = Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
                    runCatching { context.startActivity(intent) }
                },
                isLoading =
                    currentState is SyncUiState.SyncingCatchUp ||
                        currentState is SyncUiState.DiscoveringDevices,
            )
        }
    }
}
