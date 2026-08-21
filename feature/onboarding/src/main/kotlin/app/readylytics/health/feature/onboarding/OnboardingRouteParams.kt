package app.readylytics.health.feature.onboarding

import android.content.Context
import androidx.activity.compose.ManagedActivityResultLauncher
import kotlinx.coroutines.flow.Flow
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Bundles all parameters required by the onboarding routing composables to reduce
 * the number of individual parameters and comply with Detekt's LongParameterList rule.
 */
 data class OnboardingRouteParams(
    val userPreferencesFlow: Flow<app.readylytics.health.core.model.domain.preferences.UserPreferences>,
    val allPermissions: Set<String>,
    val requiredPermissions: Set<String>,
    val optionalPermissions: Set<String>,
    val onPermissionsGranted: () -> Unit,
    val onPermissionsDenied: () -> Unit,
    val onRestartApp: () -> Unit,
    val syncStatus: OnboardingSyncStatus = OnboardingSyncStatus(),
    val onboardingViewModel: OnboardingViewModel = hiltViewModel(),
    val restoreViewModel: OnboardingRestoreViewModel = hiltViewModel(),
    val syncLogViewModel: SyncLogViewModel = hiltViewModel()
 )
