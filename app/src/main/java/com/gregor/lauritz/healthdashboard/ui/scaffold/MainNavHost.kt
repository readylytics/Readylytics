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
import com.gregor.lauritz.healthdashboard.ui.about.AboutScreen
import com.gregor.lauritz.healthdashboard.ui.bloodpressure.BloodPressureDetailRoute
import com.gregor.lauritz.healthdashboard.ui.bodyfat.BodyFatDetailRoute
import com.gregor.lauritz.healthdashboard.ui.dashboard.DashboardRoute
import com.gregor.lauritz.healthdashboard.ui.navigation.AppDestination
import com.gregor.lauritz.healthdashboard.ui.navigation.TabDestination
import com.gregor.lauritz.healthdashboard.ui.heartrate.HeartRateDetailRoute
import com.gregor.lauritz.healthdashboard.ui.rhr.RestingHrDetailRoute
import com.gregor.lauritz.healthdashboard.ui.settings.SettingsRoute
import com.gregor.lauritz.healthdashboard.ui.sleep.SleepDetailRoute
import com.gregor.lauritz.healthdashboard.ui.sleep.SleepRoute
import com.gregor.lauritz.healthdashboard.ui.steps.StepDetailRoute
import com.gregor.lauritz.healthdashboard.ui.weight.WeightDetailRoute
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

            val isEnteringDetail =
                targetState.destination.hasRoute(AppDestination.WorkoutDetail::class) ||
                    targetState.destination.hasRoute(AppDestination.RestingHrDetail::class) ||
                    targetState.destination.hasRoute(AppDestination.StepDetail::class) ||
                    targetState.destination.hasRoute(AppDestination.SleepDetail::class) ||
                    targetState.destination.hasRoute(AppDestination.HeartRateDetail::class) ||
                    targetState.destination.hasRoute(AppDestination.WeightDetail::class) ||
                    targetState.destination.hasRoute(AppDestination.BodyFatDetail::class) ||
                    targetState.destination.hasRoute(AppDestination.BloodPressureDetail::class) ||
                    targetState.destination.hasRoute(AppDestination.About::class)

            val direction =
                if (isEnteringDetail) {
                    AnimatedContentTransitionScope.SlideDirection.Start
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

            val isLeavingDetail =
                initialState.destination.hasRoute(AppDestination.WorkoutDetail::class) ||
                    initialState.destination.hasRoute(AppDestination.RestingHrDetail::class) ||
                    initialState.destination.hasRoute(AppDestination.StepDetail::class) ||
                    initialState.destination.hasRoute(AppDestination.SleepDetail::class) ||
                    initialState.destination.hasRoute(AppDestination.HeartRateDetail::class) ||
                    initialState.destination.hasRoute(AppDestination.WeightDetail::class) ||
                    initialState.destination.hasRoute(AppDestination.BodyFatDetail::class) ||
                    initialState.destination.hasRoute(AppDestination.BloodPressureDetail::class) ||
                    initialState.destination.hasRoute(AppDestination.About::class)
            val isEnteringDetail =
                targetState.destination.hasRoute(AppDestination.WorkoutDetail::class) ||
                    targetState.destination.hasRoute(AppDestination.RestingHrDetail::class) ||
                    targetState.destination.hasRoute(AppDestination.StepDetail::class) ||
                    targetState.destination.hasRoute(AppDestination.SleepDetail::class) ||
                    targetState.destination.hasRoute(AppDestination.HeartRateDetail::class) ||
                    targetState.destination.hasRoute(AppDestination.WeightDetail::class) ||
                    targetState.destination.hasRoute(AppDestination.BodyFatDetail::class) ||
                    targetState.destination.hasRoute(AppDestination.BloodPressureDetail::class) ||
                    targetState.destination.hasRoute(AppDestination.About::class)

            val direction =
                if (isLeavingDetail) {
                    // Keep slide out direction consistent with pop
                    AnimatedContentTransitionScope.SlideDirection.End
                } else if (isEnteringDetail || targetIndex > initialIndex) {
                    AnimatedContentTransitionScope.SlideDirection.Start
                } else {
                    AnimatedContentTransitionScope.SlideDirection.End
                }
            fadeOut(animationSpec = tween(300)) + slideOutOfContainer(direction, tween(300))
        },
        popEnterTransition = {
            fadeIn(animationSpec = tween(300)) +
                slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.End,
                    tween(300),
                )
        },
        popExitTransition = {
            fadeOut(animationSpec = tween(300)) +
                slideOutOfContainer(
                    AnimatedContentTransitionScope.SlideDirection.End,
                    tween(300),
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
                onNavigateToHeartRate = {
                    navController.navigate(AppDestination.HeartRateDetail)
                },
                onNavigateToWeight = {
                    navController.navigate(AppDestination.WeightDetail)
                },
                onNavigateToBodyFat = {
                    navController.navigate(AppDestination.BodyFatDetail)
                },
                onNavigateToBloodPressure = {
                    navController.navigate(AppDestination.BloodPressureDetail)
                },
            )
        }
        composable<TabDestination.Sleep> {
            SleepRoute(
                onNavigateToDetail = {
                    navController.navigate(AppDestination.SleepDetail)
                },
            )
        }
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
        composable<AppDestination.SleepDetail> {
            SleepDetailRoute(
                onBack = { navController.popBackStack() },
            )
        }
        composable<AppDestination.HeartRateDetail> {
            HeartRateDetailRoute(
                onBack = { navController.popBackStack() },
            )
        }
        composable<AppDestination.WeightDetail> {
            WeightDetailRoute(
                onBack = { navController.popBackStack() },
            )
        }
        composable<AppDestination.BodyFatDetail> {
            BodyFatDetailRoute(
                onBack = { navController.popBackStack() },
            )
        }
        composable<AppDestination.BloodPressureDetail> {
            BloodPressureDetailRoute(
                onBack = { navController.popBackStack() },
            )
        }
        composable<AppDestination.About> {
            AboutScreen(
                onDismiss = { navController.popBackStack() },
            )
        }
        composable<TabDestination.Settings> {
            SettingsRoute(
                onNavigateToAbout = {
                    navController.navigate(AppDestination.About)
                },
            )
        }
    }
}

private fun getTabIndex(destination: NavDestination?): Int {
    if (destination == null) return -1
    val index =
        TabDestination.all.indexOfFirst { tab ->
            destination.hasRoute(tab::class)
        }
    if (index != -1) return index

    // WorkoutDetail is logically under Workouts
    if (destination.hasRoute(AppDestination.WorkoutDetail::class)) return 2
    // RestingHrDetail, StepDetail, HeartRateDetail, WeightDetail, BodyFatDetail, and BloodPressureDetail are logically under Dashboard
    if (destination.hasRoute(AppDestination.RestingHrDetail::class)) return 0
    if (destination.hasRoute(AppDestination.StepDetail::class)) return 0
    if (destination.hasRoute(AppDestination.HeartRateDetail::class)) return 0
    if (destination.hasRoute(AppDestination.WeightDetail::class)) return 0
    if (destination.hasRoute(AppDestination.BodyFatDetail::class)) return 0
    if (destination.hasRoute(AppDestination.BloodPressureDetail::class)) return 0
    // SleepDetail is logically under Sleep
    if (destination.hasRoute(AppDestination.SleepDetail::class)) return 1
    // About is logically under Settings
    if (destination.hasRoute(AppDestination.About::class)) return 3

    return -1
}
