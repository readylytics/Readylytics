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
import com.gregor.lauritz.healthdashboard.ui.scaffold.MainScaffold
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
                    popUpTo(AppDestination.MainShell) { inclusive = true }
                }
            SyncUiState.Unavailable ->
                navController.navigate(AppDestination.Unavailable) {
                    popUpTo(AppDestination.MainShell) { inclusive = true }
                }
            SyncUiState.PermissionsGranted ->
                navController.navigate(AppDestination.MainShell) {
                    popUpTo(AppDestination.Onboarding) { inclusive = true }
                }
            else -> Unit
        }
    }

    NavHost(
        navController = navController,
        startDestination = AppDestination.MainShell,
    ) {
        composable<AppDestination.MainShell> {
            MainScaffold()
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
