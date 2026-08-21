package app.readylytics.health.feature.onboarding

import android.content.Context
import androidx.activity.compose.ManagedActivityResultLauncher

/**
 * Bundles parameters needed for the permission gate UI.
 */
data class PermissionGateParams(
    val skipToPermissions: Boolean,
    val syncStatus: OnboardingSyncStatus,
    val permissionsDenied: Boolean,
    val missingPermissions: Set<String>,
    val autoLaunchTriggered: Boolean,
    val permissionLauncher: ManagedActivityResultLauncher<Set<String>, Set<String>>,
    val permissions: Set<String>,
    val syncLogViewModel: SyncLogViewModel,
    val logText: String,
    val context: Context,
    val onAutoLaunchTriggered: () -> Unit,
    val onPermissionsGranted: () -> Unit,
    val onPermissionsDenied: () -> Unit,
)
