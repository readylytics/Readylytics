package app.readylytics.health.ui.navigation

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.readylytics.health.MainActivity
import app.readylytics.health.R
import app.readylytics.health.feature.onboarding.OnboardingRoute
import app.readylytics.health.ui.scaffold.MainScaffold
import app.readylytics.health.ui.sync.SyncUiState
import app.readylytics.health.ui.sync.SyncViewModel

@Composable
fun AppNavHost(
    viewModel: SyncViewModel = hiltViewModel(),
    navController: NavHostController = rememberNavController(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val userPrefs by viewModel.userPreferences.collectAsStateWithLifecycle(initialValue = null)

    LaunchedEffect(uiState, userPrefs) {
        if (userPrefs == null) return@LaunchedEffect
        val currentDest = navController.currentDestination
        when (uiState) {
            SyncUiState.NeedsPermissions -> {
                if (currentDest?.hasRoute<AppDestination.Onboarding>() != true) {
                    navController.navigate(AppDestination.Onboarding) {
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
            SyncUiState.SyncingCatchUp, SyncUiState.PermissionsGranted ->
                if (currentDest?.hasRoute<AppDestination.MainShell>() != true) {
                    navController.navigate(AppDestination.MainShell) {
                        popUpTo(AppDestination.Onboarding) { inclusive = true }
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

        composable<AppDestination.Onboarding> {
            val context = LocalContext.current
            OnboardingRoute(
                userPreferencesFlow = viewModel.userPreferences,
                allPermissions = viewModel.allPermissions,
                requiredPermissions = viewModel.requiredPermissions,
                onPermissionsGranted = { viewModel.onPermissionsGranted() },
                onPermissionsDenied = { viewModel.onPermissionsDenied() },
                onRestartApp = {
                    val restartIntent =
                        Intent(context, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        }
                    context.startActivity(restartIntent)
                },
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
