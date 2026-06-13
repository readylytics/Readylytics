package app.readylytics.health.ui.onboarding

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readylytics.health.ui.sync.SyncViewModel
import kotlinx.coroutines.flow.collectLatest

@Composable
fun OnboardingRoute(
    syncViewModel: SyncViewModel,
    onboardingViewModel: OnboardingViewModel = hiltViewModel(),
    restoreViewModel: OnboardingRestoreViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val userPrefs by syncViewModel.userPreferences.collectAsStateWithLifecycle(initialValue = null)
    val restoreState by restoreViewModel.state.collectAsStateWithLifecycle()
    val permissions = remember { syncViewModel.allPermissions }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = PermissionController.createRequestPermissionResultContract(),
        ) { granted ->
            app.readylytics.health.domain.util.logD("OnboardingRoute") {
                "Permission result received. Granted: $granted"
            }
            if (granted.containsAll(syncViewModel.requiredPermissions)) {
                app.readylytics.health.domain.util.logD(
                    "OnboardingRoute",
                ) { "All required permissions granted by user" }
                syncViewModel.onPermissionsGranted()
            } else {
                val missing = syncViewModel.requiredPermissions - granted
                app.readylytics.health.domain.util.logD(
                    "OnboardingRoute",
                ) { "User denied some required permissions: $missing" }
                syncViewModel.onPermissionsDenied()
            }
        }

    LaunchedEffect(restoreViewModel.sideEffect) {
        restoreViewModel.sideEffect.collectLatest { effect ->
            when (effect) {
                OnboardingRestoreViewModel.SideEffect.RestartApp -> {
                    val restartIntent =
                        Intent(context, app.readylytics.health.MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        }
                    context.startActivity(restartIntent)
                }
            }
        }
    }

    val skipToPermissions = userPrefs?.isBirthdayConfigured == true
    var autoLaunchTriggered by rememberSaveable { mutableStateOf(false) }

    if (skipToPermissions) {
        LaunchedEffect(Unit) {
            if (!autoLaunchTriggered) {
                autoLaunchTriggered = true
                app.readylytics.health.domain.util.logD("OnboardingRoute") {
                    "Profile already configured (restored). Launching HC permissions: $permissions"
                }
                permissionLauncher.launch(permissions)
            }
        }
        FinishingSetupScreen()
        return
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
            app.readylytics.health.domain.util.logD("OnboardingRoute") {
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
                    app.readylytics.health.domain.util.logD("OnboardingRoute") {
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
        restoreState = restoreState,
        onRestoreBackupClick = restoreViewModel::restore,
        onDismissRestoreError = restoreViewModel::dismissError,
    )
}
