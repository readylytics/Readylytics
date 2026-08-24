package app.readylytics.health.feature.onboarding

import androidx.compose.runtime.Composable

/**
 * Composable that displays the appropriate gate UI based on sync status, permission state, and logs.
 * Parameters are bundled in [PermissionGateInfo] to stay within Detekt's parameter limit.
 */
@Composable
internal fun OnboardingPermissionGate(info: PermissionGateInfo) {
    // Handle auto‑launch of permission request if needed.
    HandleAutoLaunchPermissions(
        skipToPermissions = info.skipToPermissions,
        autoLaunchTriggered = info.autoLaunchTriggered,
        permissionLauncher = info.permissionLauncher,
        permissions = info.permissions,
    ) {
        info.onAutoLaunchTriggered()
    }

    // Render the UI surface for permission‑gate or sync progress.
    SyncOrPermissionGateSurface(
        gateState =
            SyncOrPermissionGateState(
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
