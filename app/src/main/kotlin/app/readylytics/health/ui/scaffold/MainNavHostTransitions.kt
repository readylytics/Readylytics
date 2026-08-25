package app.readylytics.health.ui.scaffold

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import app.readylytics.health.ui.navigation.AppDestination
import app.readylytics.health.ui.navigation.TabDestination

internal fun isDetailDestination(destination: NavDestination): Boolean =
    destination.hasRoute(AppDestination.WorkoutDetail::class) ||
        destination.hasRoute(AppDestination.StepDetail::class) ||
        destination.hasRoute(AppDestination.HeartRateDetail::class) ||
        destination.hasRoute(AppDestination.WeightDetail::class) ||
        destination.hasRoute(AppDestination.BodyFatDetail::class) ||
        destination.hasRoute(AppDestination.BloodPressureDetail::class) ||
        destination.hasRoute(AppDestination.About::class) ||
        destination.hasRoute(AppDestination.SyncProgress::class)

internal fun calculateEnterTransition(scope: AnimatedContentTransitionScope<NavBackStackEntry>): EnterTransition {
    val initialIndex = getTabIndex(scope.initialState.destination)
    val targetIndex = getTabIndex(scope.targetState.destination)
    val isEnteringDetail = isDetailDestination(scope.targetState.destination)

    val direction =
        if (isEnteringDetail || targetIndex > initialIndex) {
            AnimatedContentTransitionScope.SlideDirection.Start
        } else {
            AnimatedContentTransitionScope.SlideDirection.End
        }
    return fadeIn(animationSpec = tween(300)) + scope.slideIntoContainer(direction, tween(300))
}

internal fun calculateExitTransition(scope: AnimatedContentTransitionScope<NavBackStackEntry>): ExitTransition {
    val initialIndex = getTabIndex(scope.initialState.destination)
    val targetIndex = getTabIndex(scope.targetState.destination)
    val isLeavingDetail = isDetailDestination(scope.initialState.destination)
    val isEnteringDetail = isDetailDestination(scope.targetState.destination)

    val direction =
        if (isLeavingDetail) {
            AnimatedContentTransitionScope.SlideDirection.End
        } else if (isEnteringDetail || targetIndex > initialIndex) {
            AnimatedContentTransitionScope.SlideDirection.Start
        } else {
            AnimatedContentTransitionScope.SlideDirection.End
        }
    return fadeOut(animationSpec = tween(300)) + scope.slideOutOfContainer(direction, tween(300))
}

internal fun getTabIndex(destination: NavDestination?): Int =
    when {
        destination == null -> -1
        TabDestination.all.indexOfFirst { tab -> destination.hasRoute(tab::class) } != -1 ->
            TabDestination.all.indexOfFirst { tab -> destination.hasRoute(tab::class) }
        destination.hasRoute(AppDestination.WorkoutDetail::class) -> 3
        destination.hasRoute(AppDestination.StepDetail::class) ||
            destination.hasRoute(AppDestination.HeartRateDetail::class) ||
            destination.hasRoute(AppDestination.WeightDetail::class) ||
            destination.hasRoute(AppDestination.BodyFatDetail::class) ||
            destination.hasRoute(AppDestination.BloodPressureDetail::class) -> 0
        destination.hasRoute(AppDestination.About::class) -> 4
        else -> -1
    }
