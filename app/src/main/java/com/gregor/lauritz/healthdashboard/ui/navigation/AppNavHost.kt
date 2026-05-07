package com.gregor.lauritz.healthdashboard.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.gregor.lauritz.healthdashboard.R
import com.gregor.lauritz.healthdashboard.ui.about.AboutScreen
import com.gregor.lauritz.healthdashboard.ui.about.AboutViewModel
import com.gregor.lauritz.healthdashboard.ui.onboarding.OnboardingRoute
import com.gregor.lauritz.healthdashboard.ui.scaffold.MainScaffold
import com.gregor.lauritz.healthdashboard.ui.sync.SyncUiState
import com.gregor.lauritz.healthdashboard.ui.sync.SyncViewModel
import com.gregor.lauritz.healthdashboard.widgets.DeepLinkTarget

@Composable
fun AppNavHost(
    viewModel: SyncViewModel = hiltViewModel(),
    navController: NavHostController = rememberNavController(),
    deepLinkTarget: DeepLinkTarget? = null,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val userPrefs by viewModel.userPreferences.collectAsStateWithLifecycle(initialValue = null)

    LaunchedEffect(uiState, userPrefs) {
        val prefs = userPrefs ?: return@LaunchedEffect
        val currentDest = navController.currentDestination
        when (uiState) {
            SyncUiState.NeedsPermissions -> {
                val targetDest = if (!prefs.aboutDismissed) {
                    AppDestination.About
                } else {
                    AppDestination.Onboarding
                }
                if (currentDest?.hasRoute<AppDestination.Onboarding>() != true &&
                    currentDest?.hasRoute<AppDestination.About>() != true
                ) {
                    navController.navigate(targetDest) {
                        popUpTo(AppDestination.MainShell) { inclusive = true }
                    }
                }
            }
            SyncUiState.Unavailable ->
                if (currentDest?.hasRoute<AppDestination.Unavailable>() != true) {
                    navController.navigate(AppDestination.Unavailable) {
                        popUpTo(AppDestination.MainShell) { inclusive = true }
                    }
                }
            SyncUiState.PermissionsGranted ->
                if (currentDest?.hasRoute<AppDestination.MainShell>() != true) {
                    navController.navigate(AppDestination.MainShell) {
                        popUpTo(AppDestination.Onboarding) { inclusive = true }
                        popUpTo(AppDestination.About) { inclusive = true }
                    }
                }
            else -> Unit
        }
    }

    // Handle deep-link navigation from widgets
    LaunchedEffect(deepLinkTarget, uiState) {
        if (deepLinkTarget == null || uiState != SyncUiState.PermissionsGranted) return@LaunchedEffect

        when (deepLinkTarget) {
            is DeepLinkTarget.Metric -> {
                // Route metric deep-links to appropriate screens
                when (deepLinkTarget.type.name) {
                    "HRV", "SLEEP_SCORE", "SLEEP_DURATION", "SLEEP_EFFICIENCY" ->
                        navController.navigate(TabDestination.Sleep)
                    "RHR" -> navController.navigate(AppDestination.RestingHrDetail)
                    "READINESS", "PAI", "STRAIN_RATIO" ->
                        navController.navigate(TabDestination.Workouts)
                    "RECOVERY", "CIRCADIAN_CONSISTENCY" ->
                        navController.navigate(TabDestination.Sleep)
                    "STEPS" -> navController.navigate(AppDestination.StepDetail)
                    else -> navController.navigate(TabDestination.Dashboard)
                }
            }
            DeepLinkTarget.Dashboard -> {
                navController.navigate(TabDestination.Dashboard)
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = AppDestination.MainShell,
    ) {
        composable<AppDestination.MainShell> {
            MainScaffold()
        }

        composable<AppDestination.About> {
            val aboutViewModel: AboutViewModel = hiltViewModel()
            AboutScreen(
                onDismiss = {
                    aboutViewModel.dismissAbout {
                        navController.navigate(AppDestination.Onboarding) {
                            popUpTo(AppDestination.About) { inclusive = true }
                        }
                    }
                }
            )
        }

        composable<AppDestination.Onboarding> {
            OnboardingRoute(
                syncViewModel = viewModel
            )
        }

        composable<AppDestination.Unavailable> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.health_connect_unavailable_message),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
