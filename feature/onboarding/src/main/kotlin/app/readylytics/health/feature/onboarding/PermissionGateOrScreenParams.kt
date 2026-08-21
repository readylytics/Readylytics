package app.readylytics.health.feature.onboarding

import android.content.Context
import androidx.activity.compose.ManagedActivityResultLauncher

/**
 * Bundles all parameters needed for PermissionGateOrScreen composable.
 */
data class PermissionGateOrScreenParams(
    val skipToPermissions: Boolean,
    val syncStatus: OnboardingSyncStatus,
    val permissionsDenied: Boolean,
    val missingPermissions: Set<String>,
    val autoLaunchTriggered: Boolean,
    val permissionLauncher: ManagedActivityResultLauncher<Set<String>, Set<String>>,
    val permissions: Set<String>,
    val syncLogViewModel: SyncLogViewModel,
    val logText: String?,
    val context: Context,
    val onAutoLaunchTriggered: () -> Unit,
    val onPermissionsGranted: () -> Unit,
    val onPermissionsDenied: () -> Unit,
    val requiredPermissions: Set<String>,
    val optionalPermissions: Set<String>,
    val restoreState: OnboardingRestoreState,
    val restoreViewModel: OnboardingRestoreViewModel,
    val onboardingViewModel: OnboardingViewModel,
    val onProfileSaved: () -> Unit,
    val allPermissions: Set<String>,
)
