package com.gregor.lauritz.healthdashboard.ui.onboarding

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import com.gregor.lauritz.healthdashboard.data.healthconnect.HealthConnectRepository
import com.gregor.lauritz.healthdashboard.ui.sync.SyncViewModel

@Composable
fun OnboardingRoute(
    viewModel: SyncViewModel,
    hcRepo: HealthConnectRepository,
) {
    val context = LocalContext.current
    val permissions = remember { hcRepo.requiredPermissions }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = PermissionController.createRequestPermissionResultContract(),
        ) { granted ->
            if (granted.containsAll(permissions)) {
                viewModel.onPermissionsGranted()
            }
        }

    OnboardingScreen(
        onGrantPermissionsClick = {
            permissionLauncher.launch(permissions)
        },
        onOpenSettingsClick = {
            val intent = Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
            runCatching { context.startActivity(intent) }
        },
    )
}
