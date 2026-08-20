package app.readylytics.health.ui.health

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.contracts.ExerciseRouteRequestContract
import app.readylytics.health.core.healthconnect.data.healthconnect.toDomainRoutePoints
import app.readylytics.health.domain.model.DomainRouteLocation

/**
 * Opens Health Connect on this app's page, where "Additional access" holds the tri-state
 * "Access exercise routes" setting (Always allow / Ask every time / Don't allow). This is the only
 * surface that can grant routes permanently -- the bulk permission sheet omits them entirely.
 */
private const val ACTION_MANAGE_HEALTH_PERMISSIONS = "android.health.connect.action.MANAGE_HEALTH_PERMISSIONS"
private const val EXTRA_PACKAGE_NAME = "android.intent.extra.PACKAGE_NAME"

/**
 * Builds the "grant route access" action for a single workout.
 *
 * Requesting `READ_EXERCISE_ROUTES` through the normal permission sheet does nothing: Health Connect
 * files routes under "Additional access" rather than the data-type list, and a request for it is
 * silently dropped without ever asking the user. The supported flow is the per-session consent dialog
 * ([ExerciseRouteRequestContract]), which matches the platform's own "Ask every time" default.
 *
 * That dialog is a one-time grant returning the polyline in its result -- re-reading the session
 * afterwards still reports `ConsentRequired` -- so the points are converted here and handed to the
 * caller to persist. If the dialog cannot start at all, fall back to deep-linking into Health Connect
 * so the user can switch routes to "Always allow", which makes the session re-read carry the route
 * and stops the prompt from reappearing per workout.
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

    return { onGranted ->
        pendingOnGranted = onGranted
        try {
            perSessionConsentLauncher.launch(workoutId)
        } catch (_: Exception) {
            pendingOnGranted = null
            openExerciseRoutesSettings(context)
        }
    }
}

private fun openExerciseRoutesSettings(context: Context) {
    val appPage =
        Intent(ACTION_MANAGE_HEALTH_PERMISSIONS)
            .putExtra(EXTRA_PACKAGE_NAME, context.packageName)
    runCatching { context.startActivity(appPage) }
        .recoverCatching {
            context.startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS))
        }
}
