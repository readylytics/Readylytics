package app.readylytics.health.feature.onboarding

import android.content.Intent
import androidx.activity.compose.ManagedActivityResultLauncher
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
    userPreferencesFlow: Flow<app.readylytics.health.core.model.domain.preferences.UserPreferences>,
    allPermissions: Set<String>,
    requiredPermissions: Set<String>,
    optionalPermissions: Set<String>,
    onPermissionsGranted: () -> Unit,
    onPermissionsDenied: () -> Unit,
    onRestartApp: () -> Unit,
    syncStatus: OnboardingSyncStatus = OnboardingSyncStatus(),
    onboardingViewModel: OnboardingViewModel = hiltViewModel(),
    restoreViewModel: OnboardingRestoreViewModel = hiltViewModel(),
    syncLogViewModel: SyncLogViewModel = hiltViewModel(),
) {
    // Bundle parameters into a data class to reduce long parameter list.
    val routeParams =
        OnboardingRouteParams(
            userPreferencesFlow = userPreferencesFlow,
            allPermissions = allPermissions,
            requiredPermissions = requiredPermissions,
            optionalPermissions = optionalPermissions,
            onPermissionsGranted = onPermissionsGranted,
            onPermissionsDenied = onPermissionsDenied,
            onRestartApp = onRestartApp,
            syncStatus = syncStatus,
            onboardingViewModel = onboardingViewModel,
            restoreViewModel = restoreViewModel,
            syncLogViewModel = syncLogViewModel,
        )
    OnboardingRouteBody(routeParams)
}

private fun shouldShowPermissionGate(
    skipToPermissions: Boolean,
    syncStatus: OnboardingSyncStatus,
    permissionsDenied: Boolean,
): Boolean = skipToPermissions || syncStatus.isSyncing || syncStatus.isSyncError || permissionsDenied

@Composable
private fun PermissionGateSection(params: PermissionGateParams) {
    val gateInfo =
        PermissionGateInfo(
            skipToPermissions = params.skipToPermissions,
            syncStatus = params.syncStatus,
            permissionsDenied = params.permissionsDenied,
            missingPermissions = params.missingPermissions,
            autoLaunchTriggered = params.autoLaunchTriggered,
            permissionLauncher = params.permissionLauncher,
            permissions = params.permissions,
            syncLogViewModel = params.syncLogViewModel,
            logText = params.logText,
            context = params.context,
            onAutoLaunchTriggered = params.onAutoLaunchTriggered,
        )
    OnboardingPermissionGate(gateInfo)
}

@Composable
private fun OnboardingRouteBody(params: OnboardingRouteParams) {
    val context = LocalContext.current
    val userPrefs by params.userPreferencesFlow.collectAsStateWithLifecycle(initialValue = null)
    val restoreState by params.restoreViewModel.state.collectAsStateWithLifecycle()
    val logText by params.syncLogViewModel.logText.collectAsStateWithLifecycle()
    val permissions = remember { params.allPermissions }

    var permissionsDenied by rememberSaveable { mutableStateOf(false) }
    var missingPermissions by rememberSaveable { mutableStateOf(setOf<String>()) }

    val permissionLauncher =
        rememberOnboardingPermissionLauncher(requiredPermissions = params.requiredPermissions, onGranted = {
            permissionsDenied =
                false
            ; params.onPermissionsGranted()
        }, onDenied = { missing ->
            permissionsDenied = true
            missingPermissions = missing
            params.onPermissionsDenied()
        })

    ObserveRestartAppSideEffect(params.restoreViewModel.sideEffect, params.onRestartApp)

    var profileJustSaved by rememberSaveable { mutableStateOf(false) }
    val skipToPermissions = userPrefs?.isBirthdayConfigured == true && !profileJustSaved
    var autoLaunchTriggered by rememberSaveable { mutableStateOf(false) }

    PermissionGateOrScreen(
        PermissionGateOrScreenParams(
            skipToPermissions = skipToPermissions,
            syncStatus = params.syncStatus,
            permissionsDenied = permissionsDenied,
            missingPermissions = missingPermissions,
            autoLaunchTriggered = autoLaunchTriggered,
            permissionLauncher = permissionLauncher,
            permissions = permissions,
            syncLogViewModel = params.syncLogViewModel,
            logText = logText,
            context = context,
            onAutoLaunchTriggered = { autoLaunchTriggered = true },
            onPermissionsGranted = params.onPermissionsGranted,
            onPermissionsDenied = params.onPermissionsDenied,
            requiredPermissions = params.requiredPermissions,
            optionalPermissions = params.optionalPermissions,
            restoreState = restoreState,
            restoreViewModel = params.restoreViewModel,
            onboardingViewModel = params.onboardingViewModel,
            onProfileSaved = { profileJustSaved = true },
            allPermissions = permissions,
        ),
    )
}

@Composable
private fun PermissionGateOrScreen(params: PermissionGateOrScreenParams) {
    if (shouldShowPermissionGate(params.skipToPermissions, params.syncStatus, params.permissionsDenied)) {
        PermissionGateSection(
            PermissionGateParams(
                skipToPermissions = params.skipToPermissions,
                syncStatus = params.syncStatus,
                permissionsDenied = params.permissionsDenied,
                missingPermissions = params.missingPermissions,
                autoLaunchTriggered = params.autoLaunchTriggered,
                permissionLauncher = params.permissionLauncher,
                permissions = params.permissions,
                syncLogViewModel = params.syncLogViewModel,
                logText = params.logText,
                context = params.context,
                onAutoLaunchTriggered = params.onAutoLaunchTriggered,
                onPermissionsGranted = params.onPermissionsGranted,
                onPermissionsDenied = params.onPermissionsDenied,
            ),
        )
        return
    }

    OnboardingScreenContent(
        permissions = OnboardingPermissionsState(params.requiredPermissions, params.optionalPermissions),
        restoreCallbacks =
            OnboardingRestoreCallbacks(
                params.restoreState,
                params.restoreViewModel::restore,
                params.restoreViewModel::dismissError,
            ),
        onProfileSaved = params.onProfileSaved,
        onboardingViewModel = params.onboardingViewModel,
        permissionLauncher = params.permissionLauncher,
        allPermissions = params.allPermissions,
    )
}

@Composable
private fun ObserveRestartAppSideEffect(
    sideEffect: Flow<OnboardingRestoreViewModel.SideEffect>,
    onRestartApp: () -> Unit,
) {
    LaunchedEffect(sideEffect) {
        sideEffect.collectLatest { effect ->
            when (effect) {
                OnboardingRestoreViewModel.SideEffect.RestartApp -> onRestartApp()
            }
        }
    }
}

@Composable
private fun OnboardingScreenContent(
    permissions: OnboardingPermissionsState,
    restoreCallbacks: OnboardingRestoreCallbacks,
    onProfileSaved: () -> Unit,
    onboardingViewModel: OnboardingViewModel,
    permissionLauncher: ManagedActivityResultLauncher<Set<String>, Set<String>>,
    allPermissions: Set<String>,
) {
    val context = LocalContext.current
    OnboardingScreen(
        onProfileSetupComplete = { result, onComplete ->
            onProfileSaved()
            onboardingViewModel.saveProfile(
                birthDate = result.birthDate,
                gender = result.gender,
                physiologyProfile = result.physiologyProfile,
                dynamicColorEnabled = result.dynamicColorEnabled,
                unitSystem = result.unitSystem,
                heightCm = result.heightCm,
                onComplete = onComplete,
            )
        },
        onRetentionSetupComplete = { retentionDays ->
            onboardingViewModel.saveRetention(retentionDays) {
                permissionLauncher.launch(allPermissions)
            }
        },
        onOpenSettingsClick = {
            val intent = Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
            runCatching { context.startActivity(intent) }
        },
        permissions = permissions,
        restoreCallbacks = restoreCallbacks,
    )
}

@Composable
private fun rememberOnboardingPermissionLauncher(
    requiredPermissions: Set<String>,
    onGranted: () -> Unit,
    onDenied: (missing: Set<String>) -> Unit,
) = rememberLauncherForActivityResult(
    contract = PermissionController.createRequestPermissionResultContract(),
) { granted ->
    if (granted.containsAll(requiredPermissions)) {
        onGranted()
    } else {
        onDenied(requiredPermissions - granted)
    }
}
