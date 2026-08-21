package app.readylytics.health.feature.onboarding

import kotlinx.coroutines.flow.Flow

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
    val syncStatus: OnboardingSyncStatus,
    val onboardingViewModel: OnboardingViewModel,
    val restoreViewModel: OnboardingRestoreViewModel,
    val syncLogViewModel: SyncLogViewModel,
)
