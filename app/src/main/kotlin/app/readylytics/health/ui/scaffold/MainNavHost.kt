package app.readylytics.health.ui.scaffold

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
import app.readylytics.health.feature.about.AboutScreen
import app.readylytics.health.ui.bloodpressure.BloodPressureDetailRoute
import app.readylytics.health.ui.bodyfat.BodyFatDetailRoute
import app.readylytics.health.ui.dashboard.DashboardRoute
import app.readylytics.health.ui.heartrate.HeartRateDetailRoute
import app.readylytics.health.ui.navigation.AppDestination
import app.readylytics.health.ui.navigation.TabDestination
import app.readylytics.health.ui.settings.SettingsRoute
import app.readylytics.health.ui.sleep.SleepRoute
import app.readylytics.health.ui.steps.StepDetailRoute
import app.readylytics.health.ui.vitals.VitalsRoute
import app.readylytics.health.ui.weight.WeightDetailRoute
import app.readylytics.health.ui.workouts.WorkoutDetailRoute
import app.readylytics.health.ui.workouts.WorkoutsRoute

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
                    targetState.destination.hasRoute(AppDestination.StepDetail::class) ||
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
                    initialState.destination.hasRoute(AppDestination.StepDetail::class) ||
                    initialState.destination.hasRoute(AppDestination.HeartRateDetail::class) ||
                    initialState.destination.hasRoute(AppDestination.WeightDetail::class) ||
                    initialState.destination.hasRoute(AppDestination.BodyFatDetail::class) ||
                    initialState.destination.hasRoute(AppDestination.BloodPressureDetail::class) ||
                    initialState.destination.hasRoute(AppDestination.About::class)
            val isEnteringDetail =
                targetState.destination.hasRoute(AppDestination.WorkoutDetail::class) ||
                    targetState.destination.hasRoute(AppDestination.StepDetail::class) ||
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
                    navController.navigate(TabDestination.Vitals) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onNavigateToSteps = {
                    navController.navigate(AppDestination.StepDetail)
                },
                onNavigateToHeartRate = {
                    navController.navigate(AppDestination.HeartRateDetail)
                },
                onNavigateToHrv = {
                    navController.navigate(TabDestination.Vitals) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
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
                onNavigateToVitals = {
                    navController.navigate(TabDestination.Vitals) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
        }
        composable<TabDestination.Sleep> {
            SleepRoute()
        }
        composable<TabDestination.Vitals> {
            VitalsRoute(
                onNavigateToHrv = {},
                onNavigateToRhr = {},
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

        composable<AppDestination.StepDetail> {
            StepDetailRoute(
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
    if (destination.hasRoute(AppDestination.WorkoutDetail::class)) return 3
    // StepDetail, HeartRateDetail, WeightDetail, BodyFatDetail, and BloodPressureDetail are logically under Dashboard
    if (destination.hasRoute(AppDestination.StepDetail::class)) return 0
    if (destination.hasRoute(AppDestination.HeartRateDetail::class)) return 0
    if (destination.hasRoute(AppDestination.WeightDetail::class)) return 0
    if (destination.hasRoute(AppDestination.BodyFatDetail::class)) return 0
    if (destination.hasRoute(AppDestination.BloodPressureDetail::class)) return 0
    // About is logically under Settings
    if (destination.hasRoute(AppDestination.About::class)) return 4

    return -1
}
