package com.gregor.lauritz.healthdashboard.ui.scaffold

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.gregor.lauritz.healthdashboard.ui.dashboard.DashboardScreen
import com.gregor.lauritz.healthdashboard.ui.navigation.TabDestination
import com.gregor.lauritz.healthdashboard.ui.settings.SettingsRoute
import com.gregor.lauritz.healthdashboard.ui.sleep.SleepScreen
import com.gregor.lauritz.healthdashboard.ui.workouts.WorkoutsScreen

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
        composable<TabDestination.Dashboard> { DashboardScreen() }
        composable<TabDestination.Sleep> { SleepScreen() }
        composable<TabDestination.Workouts> { WorkoutsScreen() }
        composable<TabDestination.Settings> { SettingsRoute() }
    }
}
