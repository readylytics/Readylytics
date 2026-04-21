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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.gregor.lauritz.healthdashboard.data.healthconnect.HealthConnectRepository
import com.gregor.lauritz.healthdashboard.ui.onboarding.OnboardingRoute
import com.gregor.lauritz.healthdashboard.ui.sync.SyncUiState
import com.gregor.lauritz.healthdashboard.ui.sync.SyncViewModel

@Composable
fun AppNavHost(
    viewModel: SyncViewModel = hiltViewModel(),
    hcRepo: HealthConnectRepository,
    navController: NavHostController = rememberNavController(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState) {
        when (uiState) {
            SyncUiState.NeedsPermissions ->
                navController.navigate(AppDestination.Onboarding) {
                    popUpTo(AppDestination.Dashboard) { inclusive = true }
                }
            SyncUiState.Unavailable ->
                navController.navigate(AppDestination.Unavailable) {
                    popUpTo(AppDestination.Dashboard) { inclusive = true }
                }
            SyncUiState.PermissionsGranted ->
                navController.navigate(AppDestination.Dashboard) {
                    popUpTo(AppDestination.Onboarding) { inclusive = true }
                }
            else -> Unit
        }
    }

    NavHost(
        navController = navController,
        startDestination = AppDestination.Dashboard,
    ) {
        composable<AppDestination.Dashboard> {
            // Placeholder — Phase 5 & 6 fill this with the real Dashboard
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Dashboard coming in Phase 5", style = MaterialTheme.typography.bodyLarge)
            }
        }

        composable<AppDestination.Onboarding> {
            OnboardingRoute(viewModel = viewModel, hcRepo = hcRepo)
        }

        composable<AppDestination.Unavailable> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Health Connect is not installed on this device.\nPlease install it from the Play Store.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
