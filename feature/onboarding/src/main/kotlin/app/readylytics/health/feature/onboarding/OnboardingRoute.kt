package app.readylytics.health.feature.onboarding

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

@Composable
fun OnboardingRoute(
    userPreferencesFlow: Flow<app.readylytics.health.domain.preferences.UserPreferences>,
    allPermissions: Set<String>,
    requiredPermissions: Set<String>,
    isSyncing: Boolean = false,
    syncError: String? = null,
    onRetrySync: () -> Unit = {},
    onSkipSync: () -> Unit = {},
    onReportIssue: () -> Unit = {},
    onPermissionsGranted: () -> Unit,
    onPermissionsDenied: () -> Unit,
    onRestartApp: () -> Unit,
    onboardingViewModel: OnboardingViewModel = hiltViewModel(),
    restoreViewModel: OnboardingRestoreViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val userPrefs by userPreferencesFlow.collectAsStateWithLifecycle(initialValue = null)
    val restoreState by restoreViewModel.state.collectAsStateWithLifecycle()
    val permissions = remember { allPermissions }

    var permissionsDenied by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = PermissionController.createRequestPermissionResultContract(),
        ) { granted ->
            app.readylytics.health.domain.util.logD("OnboardingRoute") {
                "Permission result received. Granted: $granted"
            }
            if (granted.containsAll(requiredPermissions)) {
                app.readylytics.health.domain.util.logD(
                    "OnboardingRoute",
                ) { "All required permissions granted by user" }
                permissionsDenied = false
                onPermissionsGranted()
            } else {
                val missing = requiredPermissions - granted
                app.readylytics.health.domain.util.logD(
                    "OnboardingRoute",
                ) { "User denied some required permissions: $missing" }
                permissionsDenied = true
                onPermissionsDenied()
            }
        }

    LaunchedEffect(restoreViewModel.sideEffect) {
        restoreViewModel.sideEffect.collectLatest { effect ->
            when (effect) {
                OnboardingRestoreViewModel.SideEffect.RestartApp -> {
                    onRestartApp()
                }
            }
        }
    }

    val skipToPermissions = userPrefs?.isBirthdayConfigured == true
    var autoLaunchTriggered by rememberSaveable { mutableStateOf(false) }

    if (skipToPermissions || isSyncing || syncError != null) {
        LaunchedEffect(Unit) {
            if (skipToPermissions && !autoLaunchTriggered) {
                autoLaunchTriggered = true
                app.readylytics.health.domain.util.logD("OnboardingRoute") {
                    "Profile already configured (restored). Launching HC permissions: $permissions"
                }
                permissionLauncher.launch(permissions)
            }
        }
        if (permissionsDenied) {
            PermissionsRequiredScreen(
                onGrantPermissionsClick = { permissionLauncher.launch(permissions) },
                onOpenSettingsClick = {
                    val intent = Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
                    runCatching { context.startActivity(intent) }
                },
            )
        } else {
            if (syncError != null) {
                SyncErrorScreen(
                    errorMessage = syncError,
                    onRetry = onRetrySync,
                    onReportIssue = onReportIssue,
                    onSkip = onSkipSync,
                )
            } else {
                FinishingSetupScreen()
            }
        }
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
