// Deprecated duplicate file. Use PermissionGateInfo defined in PermissionGateInfo.kt

import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.runtime.Composable
import android.content.Context

/**
 * Data class bundling parameters for [OnboardingPermissionGate] to satisfy Detekt's max parameter limit.
 */
@Suppress("LongParameterList")
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

@Composable
fun OnboardingPermissionGate(info: PermissionGateInfo) {
    // Reuse existing helpers
    HandleAutoLaunchPermissions(
        skipToPermissions = info.skipToPermissions,
        autoLaunchTriggered = info.autoLaunchTriggered,
        permissionLauncher = info.permissionLauncher,
        permissions = info.permissions,
    ) {
        info.onAutoLaunchTriggered()
    }
    SyncOrPermissionGateSurface(
        gateState = SyncOrPermissionGateState(
            permissionsDenied = info.permissionsDenied,
            missingPermissions = info.missingPermissions,
            syncStatus = info.syncStatus,
            logText = info.logText,
        ),
        context = info.context,
        permissionLauncher = info.permissionLauncher,
        permissions = info.permissions,
        syncLogViewModel = info.syncLogViewModel,
    )
}
