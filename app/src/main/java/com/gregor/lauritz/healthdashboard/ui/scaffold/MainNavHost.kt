package com.gregor.lauritz.healthdashboard.ui.scaffold

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.gregor.lauritz.healthdashboard.ui.dashboard.DashboardRoute
import com.gregor.lauritz.healthdashboard.ui.navigation.TabDestination
import com.gregor.lauritz.healthdashboard.ui.settings.SettingsRoute
import com.gregor.lauritz.healthdashboard.ui.sleep.SleepRoute
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
            )
        }
        composable<TabDestination.Sleep> { SleepRoute() }
        composable<TabDestination.Workouts> { WorkoutsRoute() }
        composable<TabDestination.Settings> { SettingsRoute() }
    }
}
