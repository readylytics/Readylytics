package com.gregor.lauritz.healthdashboard.ui.scaffold

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.gregor.lauritz.healthdashboard.ui.dashboard.DashboardRoute
import com.gregor.lauritz.healthdashboard.ui.navigation.AppDestination
import com.gregor.lauritz.healthdashboard.ui.navigation.TabDestination
import com.gregor.lauritz.healthdashboard.ui.rhr.RestingHrDetailRoute
import com.gregor.lauritz.healthdashboard.ui.settings.SettingsRoute
import com.gregor.lauritz.healthdashboard.ui.sleep.SleepRoute
import com.gregor.lauritz.healthdashboard.ui.steps.StepDetailRoute
import com.gregor.lauritz.healthdashboard.ui.workouts.WorkoutDetailRoute
import com.gregor.lauritz.healthdashboard.ui.workouts.WorkoutsRoute

@Composable
fun MainNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = TabDestination.Dashboard,
        modifier = modifier,
        enterTransition = {
            val initialIndex = getTabIndex(initialState.destination)
            val targetIndex = getTabIndex(targetState.destination)

            val isEnteringDetail = targetState.destination.hasRoute(AppDestination.WorkoutDetail::class) ||
                targetState.destination.hasRoute(AppDestination.RestingHrDetail::class) ||
                targetState.destination.hasRoute(AppDestination.StepDetail::class)

            val direction = if (isEnteringDetail) {
                // Invert direction for details as requested
                AnimatedContentTransitionScope.SlideDirection.End
            } else if (targetIndex > initialIndex) {
                AnimatedContentTransitionScope.SlideDirection.Start
            } else {
                AnimatedContentTransitionScope.SlideDirection.End
            }
            fadeIn(animationSpec = tween(300)) + slideIntoContainer(direction, tween(300))
        },
        exitTransition = {
            val initialIndex = getTabIndex(initialState.destination)
            val targetIndex = getTabIndex(targetState.destination)

            val isLeavingDetail = initialState.destination.hasRoute(AppDestination.WorkoutDetail::class) ||
                initialState.destination.hasRoute(AppDestination.RestingHrDetail::class) ||
                initialState.destination.hasRoute(AppDestination.StepDetail::class)

            val direction = if (isLeavingDetail) {
                // Keep slide out direction consistent with pop
                AnimatedContentTransitionScope.SlideDirection.End
            } else if (targetIndex > initialIndex) {
                AnimatedContentTransitionScope.SlideDirection.Start
            } else {
                AnimatedContentTransitionScope.SlideDirection.End
            }
            fadeOut(animationSpec = tween(300)) + slideOutOfContainer(direction, tween(300))
        },
        popEnterTransition = {
            fadeIn(animationSpec = tween(300)) + slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.End,
                tween(300)
            )
        },
        popExitTransition = {
            fadeOut(animationSpec = tween(300)) + slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.End,
                tween(300)
            )
        },
    ) {
        composable<TabDestination.Dashboard> {
            DashboardRoute(
                onNavigateToSleep = {
                    navController.navigate(TabDestination.Sleep) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onNavigateToWorkouts = {
                    navController.navigate(TabDestination.Workouts) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onNavigateToRhr = {
                    navController.navigate(AppDestination.RestingHrDetail)
                },
                onNavigateToSteps = {
                    navController.navigate(AppDestination.StepDetail)
                },
            )
        }
        composable<TabDestination.Sleep> { SleepRoute() }
        composable<TabDestination.Workouts> {
            WorkoutsRoute { id ->
                navController.navigate(AppDestination.WorkoutDetail(id))
            }
        }
        composable<AppDestination.WorkoutDetail> { backStackEntry ->
            val detail: AppDestination.WorkoutDetail = backStackEntry.toRoute()
            WorkoutDetailRoute(
                workoutId = detail.workoutId,
                onBack = { navController.popBackStack() },
            )
        }
        composable<AppDestination.RestingHrDetail> {
            RestingHrDetailRoute(
                onBack = { navController.popBackStack() },
            )
        }
        composable<AppDestination.StepDetail> {
            StepDetailRoute(
                onBack = { navController.popBackStack() },
            )
        }
        composable<TabDestination.Settings> { SettingsRoute() }
    }
}

private fun getTabIndex(destination: NavDestination?): Int {
    if (destination == null) return -1
    val index = TabDestination.all.indexOfFirst { tab ->
        destination.hasRoute(tab::class)
    }
    if (index != -1) return index

    // WorkoutDetail is logically under Workouts
    if (destination.hasRoute(AppDestination.WorkoutDetail::class)) return 2
    // RestingHrDetail and StepDetail are logically under Dashboard
    if (destination.hasRoute(AppDestination.RestingHrDetail::class)) return 0
    if (destination.hasRoute(AppDestination.StepDetail::class)) return 0

    return -1
}
