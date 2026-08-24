package app.readylytics.health.feature.onboarding

import app.readylytics.health.core.model.data.preferences.PhysiologyProfile
import app.readylytics.health.core.model.data.preferences.UnitSystem
import app.readylytics.health.core.model.domain.sync.RecalcProgress
import java.time.LocalDate

data class OnboardingSyncStatus(
    val isSyncing: Boolean = false,
    val isSyncError: Boolean = false,
    val syncError: String? = null,
    val recalcProgress: RecalcProgress? = null,
    val onRetrySync: () -> Unit = {},
    val onSkipSync: () -> Unit = {},
    val onReportIssue: () -> Unit = {},
    val onDownloadLogs: () -> Unit = {},
    val onContinueInBackground: () -> Unit = {},
)

data class ProfileSetupResult(
    val birthDate: LocalDate,
    val gender: String,
    val physiologyProfile: PhysiologyProfile,
    val dynamicColorEnabled: Boolean,
    val unitSystem: UnitSystem,
    val heightCm: Float?,
)

data class OnboardingPermissionsState(
    val requiredPermissions: Set<String>,
    val optionalPermissions: Set<String>,
)

data class OnboardingRestoreCallbacks(
    val restoreState: OnboardingRestoreState,
    val onRestoreBackupClick: (uri: android.net.Uri, password: String) -> Unit,
    val onDismissRestoreError: () -> Unit,
)
