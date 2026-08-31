package app.readylytics.health.ui.scaffold

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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

internal enum class SyncProgressEntryAction {
    /** Auto-open the full-screen progress destination. */
    Open,

    /** Resync finished, so a previous "Continue in background" must not suppress the next run. */
    ClearDismissal,

    /** Resync is running but the user already dismissed the screen for this run. */
    None,
}

internal fun resolveSyncProgressEntryAction(
    isResyncing: Boolean,
    resyncScreenDismissed: Boolean,
): SyncProgressEntryAction =
    when {
        !isResyncing -> SyncProgressEntryAction.ClearDismissal
        resyncScreenDismissed -> SyncProgressEntryAction.None
        else -> SyncProgressEntryAction.Open
    }

@Composable
fun MainNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    // Survives navigation to/from AppDestination.SyncProgress (both destinations get
    // disposed/recomposed as they're pushed/popped) so returning from "Continue in background"
    // doesn't immediately re-trigger the Settings auto-redirect below.
    //
    // Passed down as the MutableState itself, never as its current Boolean: NavHost remembers the
    // `builder` lambda (see its KDoc -- "the contents of the builder cannot be changed"), so this
    // block runs exactly once. A Boolean read here would be frozen at `false` for the lifetime of
    // the graph and every "Continue in background" would immediately bounce back to SyncProgress.
    val resyncScreenDismissed = rememberSaveable { mutableStateOf(false) }

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
            onSetResyncScreenDismissed = { resyncScreenDismissed.value = true },
        )
        settingsDestinations(
            navController = navController,
            resyncScreenDismissed = resyncScreenDismissed,
            onResetResyncScreenDismissed = { resyncScreenDismissed.value = false },
        )
    }
}
