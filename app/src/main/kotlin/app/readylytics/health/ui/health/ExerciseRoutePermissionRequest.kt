package app.readylytics.health.ui.health

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.contracts.ExerciseRouteRequestContract
import app.readylytics.health.data.healthconnect.toDomainRoutePoints
import app.readylytics.health.domain.model.DomainRouteLocation

/** Bulk "all exercise routes" permission -- same Health Connect sheet as READ_HEALTH_DATA_HISTORY. */
private const val READ_EXERCISE_ROUTES = "android.permission.health.READ_EXERCISE_ROUTES"

/**
 * Builds the "grant route access" action for a single workout, escalating from cheap to specific:
 *
 * 1. Request the bulk [READ_EXERCISE_ROUTES] permission. One grant covers every workout forever, and
 *    it is the same sheet the user already saw during onboarding.
 * 2. If that comes back denied -- the user hard-denied it earlier, or this device's Health Connect
 *    predates the permission, in which case the OS resolves the request without showing anything --
 *    fall back to the per-session consent dialog (`ExerciseRouteRequestContract`), which works on
 *    every supported version but only covers this one workout.
 * 3. If neither launcher can start, deep-link into Health Connect settings.
 *
 * The two paths hand back different things. The bulk grant makes the route readable, so the caller
 * gets an empty list and lets its normal session re-read pick the polyline up. The per-session
 * dialog is a one-time grant that returns the polyline in its result -- a later read still reports
 * `ConsentRequired` -- so those points are passed back and must be persisted directly.
 *
 * Lives in the app module because feature modules may not import Health Connect types.
 */
@Composable
fun rememberExerciseRouteRequest(workoutId: String): ((List<DomainRouteLocation>) -> Unit) -> Unit {
    val context = LocalContext.current
    var pendingOnGranted by remember { mutableStateOf<((List<DomainRouteLocation>) -> Unit)?>(null) }

    val perSessionConsentLauncher =
        rememberLauncherForActivityResult(ExerciseRouteRequestContract()) { route ->
            val onGranted = pendingOnGranted
            pendingOnGranted = null
            // null == declined, or the session simply carries no route. Skipping the callback avoids
            // burning a full Health Connect read to land back on the identical grant card.
            if (route != null) {
                onGranted?.invoke(route.toDomainRoutePoints())
            }
        }

    val bulkPermissionLauncher =
        rememberLauncherForActivityResult(
            PermissionController.createRequestPermissionResultContract(),
        ) { granted ->
            if (granted.contains(READ_EXERCISE_ROUTES)) {
                val onGranted = pendingOnGranted
                pendingOnGranted = null
                onGranted?.invoke(emptyList())
            } else {
                launchOrFallBack(
                    launch = { perSessionConsentLauncher.launch(workoutId) },
                    onFailure = {
                        pendingOnGranted = null
                        openHealthConnectSettings(context::startActivity)
                    },
                )
            }
        }

    return { onGranted ->
        pendingOnGranted = onGranted
        launchOrFallBack(
            // Only `android.permission.health.*` strings may go in: the request contract rejects any
            // other prefix with IllegalArgumentException("Unsupported health connect permission").
            launch = { bulkPermissionLauncher.launch(setOf(READ_EXERCISE_ROUTES)) },
            onFailure = {
                launchOrFallBack(
                    launch = { perSessionConsentLauncher.launch(workoutId) },
                    onFailure = {
                        pendingOnGranted = null
                        openHealthConnectSettings(context::startActivity)
                    },
                )
            },
        )
    }
}

private inline fun launchOrFallBack(
    launch: () -> Unit,
    onFailure: () -> Unit,
) {
    try {
        launch()
    } catch (_: Exception) {
        onFailure()
    }
}

private fun openHealthConnectSettings(startActivity: (Intent) -> Unit) {
    runCatching { startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) }
}
