package com.gregor.lauritz.healthdashboard.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.gregor.lauritz.healthdashboard.data.healthconnect.HealthConnectRepository
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferencesRepository
import com.gregor.lauritz.healthdashboard.ui.about.AboutScreen
import com.gregor.lauritz.healthdashboard.ui.onboarding.OnboardingRoute
import com.gregor.lauritz.healthdashboard.ui.scaffold.MainScaffold
import com.gregor.lauritz.healthdashboard.ui.sync.SyncUiState
import com.gregor.lauritz.healthdashboard.ui.sync.SyncViewModel
import kotlinx.coroutines.launch

@Composable
fun AppNavHost(
    viewModel: SyncViewModel = hiltViewModel(),
    hcRepo: HealthConnectRepository,
    prefsRepo: UserPreferencesRepository, // Added prefsRepo
    navController: NavHostController = rememberNavController(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val userPrefs by prefsRepo.userPreferences.collectAsStateWithLifecycle(initialValue = null)

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

    NavHost(
        navController = navController,
        startDestination = AppDestination.MainShell,
    ) {
        composable<AppDestination.MainShell> {
            MainScaffold()
        }

        composable<AppDestination.About> {
            val scope = rememberCoroutineScope()
            AboutScreen(
                onDismiss = {
                    scope.launch {
                        prefsRepo.updateAboutDismissed(true)
                        navController.navigate(AppDestination.Onboarding) {
                            popUpTo(AppDestination.About) { inclusive = true }
                        }
                    }
                }
            )
        }

        composable<AppDestination.Onboarding> {
            OnboardingRoute(viewModel = viewModel, hcRepo = hcRepo, prefsRepo = prefsRepo)
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
