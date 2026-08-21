package app.readylytics.health.ui.scaffold

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import app.readylytics.health.core.model.domain.sync.RecalcProgress
import app.readylytics.health.crashreport.GithubIssueIntentResult
import app.readylytics.health.ui.navigation.TabDestination

// Holds an oversized GitHub-issue report awaiting a SAF save-file result, plus whether the
// crash-report store should be cleared once the fallback issue is opened.
internal data class PendingGithubSave(
    val oversized: GithubIssueIntentResult.Oversized,
    val consumeCrashReport: Boolean,
)

internal enum class SyncProgressDismissalState {
    StayOpen,
    MarkProgressSeen,
    Dismiss,
}

internal fun shouldAutoDismissSyncProgress(
    recalcProgress: RecalcProgress?,
    isResyncing: Boolean?,
    hasSeenProgress: Boolean,
): SyncProgressDismissalState =
    when {
        recalcProgress != null -> SyncProgressDismissalState.MarkProgressSeen
        hasSeenProgress || isResyncing == false -> SyncProgressDismissalState.Dismiss
        else -> SyncProgressDismissalState.StayOpen
    }

@Composable
fun MainNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    // Survives navigation to/from AppDestination.SyncProgress (both destinations get
    // disposed/recomposed as they're pushed/popped) so returning from "Continue in background"
    // doesn't immediately re-trigger the Settings auto-redirect below.
    var resyncScreenDismissed by rememberSaveable { mutableStateOf(false) }

    NavHost(
        navController = navController,
        startDestination = TabDestination.Dashboard,
        modifier = modifier,
        enterTransition = { calculateEnterTransition(this) },
        exitTransition = { calculateExitTransition(this) },
        popEnterTransition = {
            fadeIn(animationSpec = tween(300)) +
                slideIntoContainer(
                    androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection.End,
                    tween(300),
                )
        },
        popExitTransition = {
            fadeOut(animationSpec = tween(300)) +
                slideOutOfContainer(
                    androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection.End,
                    tween(300),
                )
        },
    ) {
        dashboardDestinations(navController)
        vitalsAndSleepDestinations(navController)
        workoutsDestinations(navController)
        aboutAndSyncProgressDestinations(
            navController = navController,
            onSetResyncScreenDismissed = { resyncScreenDismissed = true },
        )
        settingsDestinations(
            navController = navController,
            resyncScreenDismissed = resyncScreenDismissed,
            onResetResyncScreenDismissed = { resyncScreenDismissed = false },
        )
    }
}
