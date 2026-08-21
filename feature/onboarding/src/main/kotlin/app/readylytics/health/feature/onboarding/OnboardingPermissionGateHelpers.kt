package app.readylytics.health.feature.onboarding

import android.content.Context
import android.content.Intent
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.health.connect.client.HealthConnectClient

/**
 * Helper composable that auto‑launches the permission request when we need to skip directly to the
 * permissions gate and the launch has not yet been triggered.
 */
@Composable
internal fun HandleAutoLaunchPermissions(
    skipToPermissions: Boolean,
    autoLaunchTriggered: Boolean,
    permissionLauncher: ManagedActivityResultLauncher<Set<String>, Set<String>>, 
    permissions: Set<String>,
    onTriggered: () -> Unit,
) {
    LaunchedEffect(Unit) {
        if (skipToPermissions && !autoLaunchTriggered) {
            onTriggered()
            permissionLauncher.launch(permissions)
        }
    }
}

/**
 * State holder used by [SyncOrPermissionGateSurface] to render the appropriate UI based on the
 * current sync or permission status.
 */
internal data class SyncOrPermissionGateState(
    val permissionsDenied: Boolean,
    val missingPermissions: Set<String>,
    val syncStatus: OnboardingSyncStatus,
    val logText: String,
)

/**
 * UI surface that shows either a permissions‑required screen, a sync‑error screen, or the sync
 * progress UI based on the supplied [gateState].
 */
@Composable
internal fun SyncOrPermissionGateSurface(
    gateState: SyncOrPermissionGateState,
    context: Context,
    permissionLauncher: ManagedActivityResultLauncher<Set<String>, Set<String>>, 
    permissions: Set<String>,
    syncLogViewModel: SyncLogViewModel,
) {
    Surface(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        when {
            gateState.permissionsDenied -> {
                PermissionsRequiredScreen(
                    onRecheckPermissionsClick = { permissionLauncher.launch(permissions) },
                    onOpenSettingsClick = {
                        val intent = Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
                        runCatching { context.startActivity(intent) }
                    },
                    missingPermissions = gateState.missingPermissions,
                )
            }
            gateState.syncStatus.isSyncError -> {
                SyncErrorScreen(
                    errorMessage = gateState.syncStatus.syncError,
                    onRetry = gateState.syncStatus.onRetrySync,
                    onReportIssue = gateState.syncStatus.onReportIssue,
                    onSkip = gateState.syncStatus.onSkipSync,
                )
            }
            else -> {
                SyncProgressScreen(
                    progress = gateState.syncStatus.recalcProgress,
                    onDownloadLogs = gateState.syncStatus.onDownloadLogs,
                    onContinueInBackground = gateState.syncStatus.onContinueInBackground,
                    logText = gateState.logText,
                    onLogsVisibilityChanged = { visible ->
                        if (visible) syncLogViewModel.startPolling() else syncLogViewModel.stopPolling()
                    },
                )
            }
        }
    }
}
