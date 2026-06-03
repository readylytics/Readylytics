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
import com.gregor.lauritz.healthdashboard.ui.sync.SyncViewModel

@Composable
fun OnboardingRoute(
    syncViewModel: SyncViewModel,
    onboardingViewModel: OnboardingViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val userPrefs by syncViewModel.userPreferences.collectAsStateWithLifecycle(initialValue = null)
    val permissions = remember { syncViewModel.allPermissions }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = PermissionController.createRequestPermissionResultContract(),
        ) { granted ->
            com.gregor.lauritz.healthdashboard.domain.util.logD("OnboardingRoute") {
                "Permission result received. Granted: $granted"
            }
            if (granted.containsAll(syncViewModel.requiredPermissions)) {
                com.gregor.lauritz.healthdashboard.domain.util.logD(
                    "OnboardingRoute",
                ) { "All required permissions granted by user" }
                syncViewModel.onPermissionsGranted()
            } else {
                val missing = syncViewModel.requiredPermissions - granted
                com.gregor.lauritz.healthdashboard.domain.util.logD(
                    "OnboardingRoute",
                ) { "User denied some required permissions: $missing" }
                syncViewModel.onPermissionsDenied()
            }
        }

    OnboardingScreen(
        onGrantPermissionsClick = {
            birthDate,
            gender,
            physiologyProfile,
            dynamicColorEnabled,
            unitSystem,
            heightCm,
            ->
            com.gregor.lauritz.healthdashboard.domain.util.logD("OnboardingRoute") {
                "Grant Access clicked. Saving profile first..."
            }
            onboardingViewModel.saveProfile(
                birthDate = birthDate,
                gender = gender,
                physiologyProfile = physiologyProfile,
                dynamicColorEnabled = dynamicColorEnabled,
                unitSystem = unitSystem,
                heightCm = heightCm,
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
    )
}
