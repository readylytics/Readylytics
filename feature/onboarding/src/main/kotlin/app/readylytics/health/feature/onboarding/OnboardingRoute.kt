package app.readylytics.health.feature.onboarding

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readylytics.health.core.ui.sync.SyncProgressScreen
import app.readylytics.health.domain.sync.RecalcProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

@Composable
fun OnboardingRoute(
    userPreferencesFlow: Flow<app.readylytics.health.domain.preferences.UserPreferences>,
    allPermissions: Set<String>,
    requiredPermissions: Set<String>,
    isSyncing: Boolean = false,
    isSyncError: Boolean = false,
    syncError: String? = null,
    recalcProgress: RecalcProgress? = null,
    onRetrySync: () -> Unit = {},
    onSkipSync: () -> Unit = {},
    onReportIssue: () -> Unit = {},
    onPermissionsGranted: () -> Unit,
    onPermissionsDenied: () -> Unit,
    onRestartApp: () -> Unit,
    onDownloadLogs: () -> Unit = {},
    onContinueInBackground: () -> Unit = {},
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

    // True once the user has saved their profile in THIS session — keeps skipToPermissions
    // from firing before they've seen the RetentionSetupScreen.
    var profileJustSaved by rememberSaveable { mutableStateOf(false) }

    // Fast-path for restore flow: profile already existed before this session started
    // (isBirthdayConfigured was true on first load AND user didn't just fill the profile form).
    val skipToPermissions = userPrefs?.isBirthdayConfigured == true && !profileJustSaved
    var autoLaunchTriggered by rememberSaveable { mutableStateOf(false) }

    if (skipToPermissions || isSyncing || isSyncError) {
        LaunchedEffect(Unit) {
            if (skipToPermissions && !autoLaunchTriggered) {
                autoLaunchTriggered = true
                app.readylytics.health.domain.util.logD("OnboardingRoute") {
                    "Profile already configured (restored). Launching HC permissions: $permissions"
                }
                permissionLauncher.launch(permissions)
            }
        }
        Surface(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            if (permissionsDenied) {
                PermissionsRequiredScreen(
                    onGrantPermissionsClick = { permissionLauncher.launch(permissions) },
                    onOpenSettingsClick = {
                        val intent = Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
                        runCatching { context.startActivity(intent) }
                    },
                )
            } else {
                if (isSyncError) {
                    SyncErrorScreen(
                        errorMessage = syncError,
                        onRetry = onRetrySync,
                        onReportIssue = onReportIssue,
                        onSkip = onSkipSync,
                    )
                } else {
                    SyncProgressScreen(
                        progress = recalcProgress,
                        onDownloadLogs = onDownloadLogs,
                        onContinueInBackground = onContinueInBackground,
                    )
                }
            }
        }
        return
    }

    OnboardingScreen(
        onProfileSetupComplete = {
            birthDate,
            gender,
            physiologyProfile,
            dynamicColorEnabled,
            unitSystem,
            heightCm,
            onComplete,
            ->
            // Set BEFORE saveProfile so the DataStore write cannot race and trigger
            // skipToPermissions while profileJustSaved is still false.
            profileJustSaved = true
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
                onComplete = onComplete,
            )
        },
        onRetentionSetupComplete = { retentionDays ->
            onboardingViewModel.saveRetention(retentionDays) {
                app.readylytics.health.domain.util.logD("OnboardingRoute") {
                    "Retention saved. Launching HC permissions: $permissions"
                }
                permissionLauncher.launch(permissions)
            }
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
