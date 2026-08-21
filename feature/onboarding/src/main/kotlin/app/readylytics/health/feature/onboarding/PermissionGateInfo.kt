package app.readylytics.health.feature.onboarding

import android.content.Context
import androidx.activity.compose.ManagedActivityResultLauncher

/**
 * Data class bundling parameters for [OnboardingPermissionGate] to satisfy Detekt's max parameter limit.
 */
internal data class PermissionGateInfo(
    val skipToPermissions: Boolean,
    val syncStatus: OnboardingSyncStatus,
    val permissionsDenied: Boolean,
    val missingPermissions: Set<String>,
    val autoLaunchTriggered: Boolean,
    val permissionLauncher: ManagedActivityResultLauncher<Set<String>>,
    val permissions: Set<String>,
    val syncLogViewModel: SyncLogViewModel,
    val logText: String,
    val context: Context,
    val onAutoLaunchTriggered: () -> Unit,
)
