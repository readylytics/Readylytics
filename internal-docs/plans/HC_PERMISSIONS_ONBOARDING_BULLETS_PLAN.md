# Onboarding: Bigger HC-Permissions Text + Bullet Lists + Grant-Permissions Fix — Plan

**Status:** PLAN — awaiting approval/pickup. No implementation code has been written.
**Date:** 2026-08-12
**Branch this plan lives on:** `claude/hc-permissions-onboarding-plan` (separate from the
implementation branch `claude/hc-permissions-styling-2zgqrc`, so the plan can be reviewed
independently before code lands).

This document is self-contained: it does not assume any prior chat context. It includes full
current file contents for every file that will be touched, the exact target changes, and how to
verify the result.

---

## 1. Why

Two onboarding screens list required Health Connect permissions as dense inline prose instead of
a scannable list, and the "Health Connect permissions" section text on the retention screen reads
too small relative to the rest of the page.

1. **Retention/permissions setup screen** (`RetentionSetupScreen.kt`) — the "Health Connect
   permissions" section has a small title (`titleSmall`), small body text (`bodySmall`), and
   crams all four data types into one sentence: *"This app needs access to Sleep, Heart Rate,
   HRV, and Exercise data from Health Connect to calculate your scores."*
2. **"Permissions needed" screen** (`PermissionsRequiredScreen` in `OnboardingScreen.kt`) — shown
   when the user denies some required Health Connect permissions during the system permission
   dialog. It currently shows a static, generic message that never says *which* permissions were
   actually denied: *"Readylytics needs Health Connect permissions to continue. Grant access to
   finish setting up your restored data."*
3. **Bug report:** on that same "Permissions needed" screen, the **"Grant Permissions" button
   does not work** — tapping it produces no visible effect. Root-caused via code review in
   §3.6 below (real, reproducible-by-inspection bug, distinct from screens 1/2's styling work).

### Requested outcome
- On screen 1: bigger title, bigger body text, and each of the four permissions (Sleep, Heart
  Rate, HRV, Exercise) as its own bullet point instead of one sentence.
- On screen 2: make the message **dynamic** — show the *specific* missing required permissions as
  bullet points, computed from the actual Health Connect permission-grant result rather than a
  generic paragraph.
- Fix the "Grant Permissions" button so it actually re-opens the Health Connect permission request
  in every flow that can reach this screen, not just the restore/already-configured flow.

---

## 2. Relevant existing code (full context)

### 2.1 `feature/onboarding/src/main/res/values/strings.xml` (current, full file)

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="onboarding_welcome_title">Welcome to Readylytics</string>
    <string name="onboarding_welcome_subtitle">Your personal recovery &amp; readiness tracker —
        powered entirely by your own data.</string>
    <string name="onboarding_feature_sleep_title">Sleep Score</string>
    <string name="onboarding_feature_sleep_desc">Weighted by duration, architecture, and restoration
        quality.</string>
    <string name="onboarding_feature_hrv_title">HRV &amp; resting HR tracking</string>
    <string name="onboarding_feature_hrv_desc">Personal baselines calculated from your last 30 days
        of data.</string>
    <string name="onboarding_feature_training_title">Training load index</string>
    <string name="onboarding_feature_training_desc">Acute vs. chronic workload ratio to guide your
        recovery.</string>
    <string name="onboarding_privacy_note">All data stays on your device. Nothing is ever uploaded
        to any server.</string>
    <string name="onboarding_get_started">Get Started</string>
    <string name="onboarding_profile_title">Your profile</string>
    <string name="onboarding_profile_subtitle">Your birthday, gender, and activity profile are used
        to calculate your max heart rate and personalize scores. They are stored only on your
        device.</string>
    <string name="onboarding_activity_profile_label">Activity profile</string>
    <string name="onboarding_appearance_label">Appearance</string>
    <string name="onboarding_units_label">Measurement units</string>
    <string name="onboarding_hc_permissions_label">Health Connect permissions</string>
    <string name="onboarding_hc_permissions_desc">This app needs access to Sleep, Heart Rate, HRV,
        and Exercise data from Health Connect to calculate your scores.</string>
    <string name="onboarding_grant_access">Grant Access &amp; Continue</string>
    <string name="onboarding_next">Next</string>
    <string name="onboarding_open_hc_settings">Open Health Connect Settings</string>
    <string name="onboarding_restore_backup_button">Restore from Backup</string>
    <string name="onboarding_restore_title">Restore from Backup</string>
    <string name="onboarding_restore_subtitle">Select a backup file and enter its password to
        restore your data.</string>
    <string name="onboarding_restore_select_file_button">Select backup file</string>
    <string name="onboarding_restore_password_label">Backup password</string>
    <string name="onboarding_restore_button">Restore</string>
    <string name="onboarding_permissions_required_title">Permissions needed</string>
    <string name="onboarding_permissions_required_message">Readylytics needs Health Connect
        permissions to continue. Grant access to finish setting up your restored data.</string>
    <string name="onboarding_grant_permissions_retry">Grant Permissions</string>
    <string name="privacy_rationale_title">Privacy &amp; Health Data</string>
    <string name="privacy_rationale_description">This app reads sleep, heart rate, heart rate
        variability, and exercise data from Health Connect solely to compute your personal recovery
        and readiness scores. All data is processed locally on your device and is never uploaded to
        any server.</string>
    <string name="onboarding_sync_error_title">Sync failed</string>
    <string name="onboarding_sync_error_message">An error occurred while fetching your Health Connect data. You can retry the sync now, or proceed to the app and trigger a full resync from settings later.</string>
    <string name="onboarding_sync_error_retry">Retry Sync</string>
    <string name="onboarding_sync_error_report">Report Issue</string>
    <string name="onboarding_sync_error_skip">Proceed to App</string>
    <string name="onboarding_retention_title">How far back should we sync?</string>
    <string name="onboarding_retention_subtitle">Readylytics will backfill historical data from Health Connect up to this period. Surfacing older data might take longer on the first sync.</string>
    <string name="onboarding_retention_continue">Continue</string>
</resources>
```

Grep confirms `onboarding_hc_permissions_label`/`onboarding_hc_permissions_desc` are referenced
**only** from `RetentionSetupScreen.kt`, and `PermissionsRequiredScreen` (which uses
`onboarding_permissions_required_title`/`_message`) is referenced **only** from
`OnboardingRoute.kt` (call site) and defined in `OnboardingScreen.kt`. No other screens are
affected by this plan.

### 2.2 `feature/onboarding/src/main/kotlin/app/readylytics/health/feature/onboarding/RetentionSetupScreen.kt` (current, full file)

```kotlin
package app.readylytics.health.feature.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.components.settings.RetentionSlider
import app.readylytics.health.data.preferences.SettingsDefaults

@Composable
fun RetentionSetupScreen(
    onContinueClick: (retentionDays: Int) -> Unit,
    onOpenSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var retentionDays by remember { mutableIntStateOf(SettingsDefaults.RETENTION_DAYS) }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(MaterialTheme.spacing.pageSectionGapLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringResource(R.string.onboarding_retention_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))

            Text(
                text = stringResource(R.string.onboarding_retention_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(MaterialTheme.spacing.large))

            RetentionSlider(
                enabled = true,
                retentionDays = retentionDays,
                onEnabledChanged = {},
                onRetentionDaysChanged = { retentionDays = it },
                showEnableToggle = false,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapLarge))

            Text(
                text = stringResource(R.string.onboarding_hc_permissions_label),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(MaterialTheme.spacing.extraSmall))
            Text(
                text = stringResource(R.string.onboarding_hc_permissions_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGap))

        Button(
            onClick = { onContinueClick(retentionDays) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.onboarding_grant_access))
        }

        Spacer(Modifier.height(MaterialTheme.spacing.small))

        TextButton(
            onClick = onOpenSettingsClick,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.onboarding_open_hc_settings))
        }
    }
}
```

### 2.3 `feature/onboarding/src/main/kotlin/app/readylytics/health/feature/onboarding/OnboardingScreen.kt` (relevant excerpts, current)

Full file is ~440 lines; the relevant parts are the imports header, `PermissionsRequiredScreen`,
and the private `FeatureItem` helper it's next to (shown for style reference — not modified):

```kotlin
package app.readylytics.health.feature.onboarding

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.components.SettingsToggleItem
import app.readylytics.health.core.ui.components.settings.BirthdayDatePickerField
import app.readylytics.health.core.ui.components.settings.PhysiologyProfilePicker
import app.readylytics.health.core.ui.settings.HeightInputField
import app.readylytics.health.core.ui.settings.common.UnitSystemSelector
import app.readylytics.health.data.preferences.PhysiologyProfile
import app.readylytics.health.data.preferences.UnitSystem
import app.readylytics.health.feature.onboarding.R
import java.time.LocalDate
import app.readylytics.health.core.ui.R as CoreR

// ... OnboardingScreen(...) composable (routes step 0-3, unrelated to this plan) ...

// ... private fun WelcomeScreen(...) (unrelated to this plan) ...

@Composable
fun PermissionsRequiredScreen(
    onGrantPermissionsClick: () -> Unit,
    onOpenSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(MaterialTheme.spacing.pageSectionGapLarge)
                .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.onboarding_permissions_required_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))

        Text(
            text = stringResource(R.string.onboarding_permissions_required_message),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapLarge))

        Button(
            onClick = onGrantPermissionsClick,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.onboarding_grant_permissions_retry))
        }

        Spacer(Modifier.height(MaterialTheme.spacing.small))

        TextButton(onClick = onOpenSettingsClick) {
            Text(stringResource(R.string.onboarding_open_hc_settings))
        }
    }
}

@Composable
private fun FeatureItem(
    icon: @Composable () -> Unit,
    title: String,
    description: String,
) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.titleSmall) },
        supportingContent = { Text(description, style = MaterialTheme.typography.bodySmall) },
        leadingContent = icon,
    )
}

// ... private fun ProfileSetupScreen(...) (unrelated to this plan) ...
```

### 2.4 `feature/onboarding/src/main/kotlin/app/readylytics/health/feature/onboarding/OnboardingRoute.kt` (current, full file)

```kotlin
package app.readylytics.health.feature.onboarding

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readylytics.health.core.ui.sync.SyncProgressScreen
import app.readylytics.health.domain.sync.RecalcProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

@Composable
fun OnboardingRoute(
    userPreferencesFlow: Flow<app.readylytics.health.domain.preferences.UserPreferences>,
    allPermissions: Set<String>,
    requiredPermissions: Set<String>,
    isSyncing: Boolean = false,
    isSyncError: Boolean = false,
    syncError: String? = null,
    recalcProgress: RecalcProgress? = null,
    onRetrySync: () -> Unit = {},
    onSkipSync: () -> Unit = {},
    onReportIssue: () -> Unit = {},
    onPermissionsGranted: () -> Unit,
    onPermissionsDenied: () -> Unit,
    onRestartApp: () -> Unit,
    onDownloadLogs: () -> Unit = {},
    onContinueInBackground: () -> Unit = {},
    onboardingViewModel: OnboardingViewModel = hiltViewModel(),
    restoreViewModel: OnboardingRestoreViewModel = hiltViewModel(),
    syncLogViewModel: SyncLogViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val userPrefs by userPreferencesFlow.collectAsStateWithLifecycle(initialValue = null)
    val restoreState by restoreViewModel.state.collectAsStateWithLifecycle()
    val logText by syncLogViewModel.logText.collectAsStateWithLifecycle()
    val permissions = remember { allPermissions }

    var permissionsDenied by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = PermissionController.createRequestPermissionResultContract(),
        ) { granted ->
            app.readylytics.health.domain.util.logD("OnboardingRoute") {
                "Permission result received. Granted: $granted"
            }
            if (granted.containsAll(requiredPermissions)) {
                app.readylytics.health.domain.util.logD(
                    "OnboardingRoute",
                ) { "All required permissions granted by user" }
                permissionsDenied = false
                onPermissionsGranted()
            } else {
                val missing = requiredPermissions - granted
                app.readylytics.health.domain.util.logD(
                    "OnboardingRoute",
                ) { "User denied some required permissions: $missing" }
                permissionsDenied = true
                onPermissionsDenied()
            }
        }

    LaunchedEffect(restoreViewModel.sideEffect) {
        restoreViewModel.sideEffect.collectLatest { effect ->
            when (effect) {
                OnboardingRestoreViewModel.SideEffect.RestartApp -> {
                    onRestartApp()
                }
            }
        }
    }

    // True once the user has saved their profile in THIS session — keeps skipToPermissions
    // from firing before they've seen the RetentionSetupScreen.
    var profileJustSaved by rememberSaveable { mutableStateOf(false) }

    // Fast-path for restore flow: profile already existed before this session started
    // (isBirthdayConfigured was true on first load AND user didn't just fill the profile form).
    val skipToPermissions = userPrefs?.isBirthdayConfigured == true && !profileJustSaved
    var autoLaunchTriggered by rememberSaveable { mutableStateOf(false) }

    if (skipToPermissions || isSyncing || isSyncError) {
        LaunchedEffect(Unit) {
            if (skipToPermissions && !autoLaunchTriggered) {
                autoLaunchTriggered = true
                app.readylytics.health.domain.util.logD("OnboardingRoute") {
                    "Profile already configured (restored). Launching HC permissions: $permissions"
                }
                permissionLauncher.launch(permissions)
            }
        }
        Surface(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            if (permissionsDenied) {
                PermissionsRequiredScreen(
                    onGrantPermissionsClick = { permissionLauncher.launch(permissions) },
                    onOpenSettingsClick = {
                        val intent = Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
                        runCatching { context.startActivity(intent) }
                    },
                )
            } else {
                if (isSyncError) {
                    SyncErrorScreen(
                        errorMessage = syncError,
                        onRetry = onRetrySync,
                        onReportIssue = onReportIssue,
                        onSkip = onSkipSync,
                    )
                } else {
                    SyncProgressScreen(
                        progress = recalcProgress,
                        onDownloadLogs = onDownloadLogs,
                        onContinueInBackground = onContinueInBackground,
                        logText = logText,
                        onLogsVisibilityChanged = { visible ->
                            if (visible) syncLogViewModel.startPolling() else syncLogViewModel.stopPolling()
                        },
                    )
                }
            }
        }
        return
    }

    OnboardingScreen(
        onProfileSetupComplete = {
            birthDate,
            gender,
            physiologyProfile,
            dynamicColorEnabled,
            unitSystem,
            heightCm,
            onComplete,
            ->
            // Set BEFORE saveProfile so the DataStore write cannot race and trigger
            // skipToPermissions while profileJustSaved is still false.
            profileJustSaved = true
            app.readylytics.health.domain.util.logD("OnboardingRoute") {
                "Grant Access clicked. Saving profile first..."
            }
            onboardingViewModel.saveProfile(
                birthDate = birthDate,
                gender = gender,
                physiologyProfile = physiologyProfile,
                dynamicColorEnabled = dynamicColorEnabled,
                unitSystem = unitSystem,
                heightCm = heightCm,
                onComplete = onComplete,
            )
        },
        onRetentionSetupComplete = { retentionDays ->
            onboardingViewModel.saveRetention(retentionDays) {
                app.readylytics.health.domain.util.logD("OnboardingRoute") {
                    "Retention saved. Launching HC permissions: $permissions"
                }
                permissionLauncher.launch(permissions)
            }
        },
        onOpenSettingsClick = {
            val intent = Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
            runCatching { context.startActivity(intent) }
        },
        restoreState = restoreState,
        onRestoreBackupClick = restoreViewModel::restore,
        onDismissRestoreError = restoreViewModel::dismissError,
    )
}
```

Note: `val missing = requiredPermissions - granted` is already computed on the denied path — it's
just logged and discarded today. This plan captures it into state and threads it to the screen.

### 2.5 `core/healthconnect/.../HealthConnectRepositoryImpl.kt` (relevant excerpt — defines the permission sets)

```kotlin
override val criticalPermissions: Set<String> =
    setOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
    )

override val requiredPermissions: Set<String> =
    criticalPermissions +
        setOf("android.permission.health.READ_HEALTH_DATA_HISTORY")

override val optionalPermissions: Set<String> =
    setOf(
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(BodyFatRecord::class),
        HealthPermission.getReadPermission(BloodPressureRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        HealthPermission.getReadPermission(BodyTemperatureRecord::class),
    )

override val allPermissions: Set<String> =
    requiredPermissions + optionalPermissions
```

So `requiredPermissions` (the set the missing-permissions screen cares about) is always exactly:
Sleep, Heart Rate, HRV, Exercise, Steps, and the `READ_HEALTH_DATA_HISTORY` string permission (no
SDK constant exists for this last one anywhere in the codebase — it's always used as the raw
string literal `"android.permission.health.READ_HEALTH_DATA_HISTORY"`, e.g. in
`AndroidManifest.xml`, `BenchmarkTestSupport.kt`, `RootNavigationTest.kt`).

`feature/onboarding/build.gradle.kts` already depends on `libs.androidx.health.connect.client`, so
`HealthPermission`/`SleepSessionRecord`/etc. are available in `feature/onboarding` without adding
a new dependency.

### 2.6 Existing bullet-point precedent: `feature/about/.../AboutComponents.kt:74-95`

```kotlin
@Composable
fun BulletItem(text: String) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.pageHorizontal, vertical = MaterialTheme.spacing.hairline),
    ) {
        Text(
            text = "•",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = MaterialTheme.spacing.pageSectionGapSmall),
        )
        Text(
            text = parseMarkdown(text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}
```

This lives in `feature/about`, a different Gradle module from `feature/onboarding`. Importing it
would add a feature-to-feature module dependency that doesn't currently exist, so this plan
re-implements the same visual convention locally in `feature/onboarding` instead of importing it.
No reusable bullet component exists in `core/ui` or `core/designsystem` (checked both — the only
list pattern in `core/ui` is unrelated `ListItem` usage, and `core/designsystem` only has
`Spacing.kt`/theme files).

### 2.7 `core/designsystem/.../Spacing.kt` (relevant tokens)

```kotlin
val extraSmall: Dp = 4.dp
val extraSmallMedium: Dp = 6.dp
val small: Dp = 8.dp
val smallMedium: Dp = 12.dp
val medium: Dp = 16.dp
val large: Dp = 24.dp
// ... plus semantic aliases:
// pageSectionGap == medium, pageSectionGapSmall == small, pageSectionGapLarge == large
// hairline = 2.dp
```

---

## 3. Target changes

### 3.1 `feature/onboarding/src/main/res/values/strings.xml`

Replace:

```xml
    <string name="onboarding_hc_permissions_desc">This app needs access to Sleep, Heart Rate, HRV,
        and Exercise data from Health Connect to calculate your scores.</string>
```

with:

```xml
    <string name="onboarding_hc_permissions_desc">This app needs access to the following data from
        Health Connect to calculate your scores:</string>
    <string name="onboarding_hc_permission_sleep">Sleep</string>
    <string name="onboarding_hc_permission_heart_rate">Heart Rate</string>
    <string name="onboarding_hc_permission_hrv">Heart Rate Variability (HRV)</string>
    <string name="onboarding_hc_permission_exercise">Exercise</string>
    <string name="onboarding_hc_permission_steps">Steps</string>
    <string name="onboarding_hc_permission_history">Historical Data Access</string>
    <string name="onboarding_missing_permissions_label">Missing permissions:</string>
```

(`onboarding_hc_permission_steps`/`_history` are only used by the missing-permissions screen —
the retention screen intentionally keeps listing just the 4 CLAUDE.md-called-out types, matching
today's copy. `onboarding_hc_permissions_label`, `onboarding_permissions_required_title`, and
`onboarding_permissions_required_message` are unchanged text-wise; only their Compose styling and
surrounding composition change.)

### 3.2 New file: `feature/onboarding/src/main/kotlin/app/readylytics/health/feature/onboarding/PermissionBullets.kt`

```kotlin
package app.readylytics.health.feature.onboarding

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import app.readylytics.health.core.designsystem.spacing

@Composable
internal fun PermissionBulletRow(
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth().padding(vertical = MaterialTheme.spacing.hairline)) {
        Text(
            text = "•",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = MaterialTheme.spacing.pageSectionGapSmall),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@StringRes
internal fun healthPermissionLabelRes(permission: String): Int? =
    when (permission) {
        HealthPermission.getReadPermission(SleepSessionRecord::class) -> R.string.onboarding_hc_permission_sleep
        HealthPermission.getReadPermission(HeartRateRecord::class) -> R.string.onboarding_hc_permission_heart_rate
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class) ->
            R.string.onboarding_hc_permission_hrv
        HealthPermission.getReadPermission(ExerciseSessionRecord::class) -> R.string.onboarding_hc_permission_exercise
        HealthPermission.getReadPermission(StepsRecord::class) -> R.string.onboarding_hc_permission_steps
        "android.permission.health.READ_HEALTH_DATA_HISTORY" -> R.string.onboarding_hc_permission_history
        else -> null
    }
```

This covers every permission `HealthConnectRepositoryImpl.requiredPermissions` can contain, so
`healthPermissionLabelRes` never silently drops a bullet for a permission that's actually part of
the required set.

### 3.3 `RetentionSetupScreen.kt` — permissions block edit

Replace (current lines 77–88):

```kotlin
            Text(
                text = stringResource(R.string.onboarding_hc_permissions_label),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(MaterialTheme.spacing.extraSmall))
            Text(
                text = stringResource(R.string.onboarding_hc_permissions_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
```

with:

```kotlin
            Text(
                text = stringResource(R.string.onboarding_hc_permissions_label),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(MaterialTheme.spacing.extraSmall))
            Text(
                text = stringResource(R.string.onboarding_hc_permissions_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(MaterialTheme.spacing.small))
            PermissionBulletRow(stringResource(R.string.onboarding_hc_permission_sleep))
            PermissionBulletRow(stringResource(R.string.onboarding_hc_permission_heart_rate))
            PermissionBulletRow(stringResource(R.string.onboarding_hc_permission_hrv))
            PermissionBulletRow(stringResource(R.string.onboarding_hc_permission_exercise))
```

Title style `titleSmall` → `titleMedium` (next step up the M3 scale; stays visually distinct from
the page's `headlineSmall` title). Body style `bodySmall` → `bodyMedium`. `PermissionBulletRow` is
in the same package (`app.readylytics.health.feature.onboarding`), so no new import is required
for it specifically. No other part of this screen (retention slider, buttons) changes.

### 3.4 `OnboardingScreen.kt` — `PermissionsRequiredScreen` edit

Replace the current signature/body:

```kotlin
@Composable
fun PermissionsRequiredScreen(
    onGrantPermissionsClick: () -> Unit,
    onOpenSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(MaterialTheme.spacing.pageSectionGapLarge)
                .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.onboarding_permissions_required_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))

        Text(
            text = stringResource(R.string.onboarding_permissions_required_message),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapLarge))

        Button(
            onClick = onGrantPermissionsClick,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.onboarding_grant_permissions_retry))
        }

        Spacer(Modifier.height(MaterialTheme.spacing.small))

        TextButton(onClick = onOpenSettingsClick) {
            Text(stringResource(R.string.onboarding_open_hc_settings))
        }
    }
}
```

with:

```kotlin
@Composable
fun PermissionsRequiredScreen(
    onGrantPermissionsClick: () -> Unit,
    onOpenSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    missingPermissions: Set<String> = emptySet(),
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(MaterialTheme.spacing.pageSectionGapLarge)
                .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.onboarding_permissions_required_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))

        Text(
            text = stringResource(R.string.onboarding_permissions_required_message),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val missingLabelRes = missingPermissions.mapNotNull { healthPermissionLabelRes(it) }
        if (missingLabelRes.isNotEmpty()) {
            Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGap))
            Text(
                text = stringResource(R.string.onboarding_missing_permissions_label),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(MaterialTheme.spacing.extraSmall))
            missingLabelRes.forEach { labelRes ->
                PermissionBulletRow(stringResource(labelRes), modifier = Modifier.fillMaxWidth())
            }
        }

        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapLarge))

        Button(
            onClick = onGrantPermissionsClick,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.onboarding_grant_permissions_retry))
        }

        Spacer(Modifier.height(MaterialTheme.spacing.small))

        TextButton(onClick = onOpenSettingsClick) {
            Text(stringResource(R.string.onboarding_open_hc_settings))
        }
    }
}
```

Note: the bullet section is center-column content but the bullet rows themselves should read
left-aligned as a list (matching `RetentionSetupScreen`'s treatment) — `Modifier.fillMaxWidth()`
on each `PermissionBulletRow` combined with the parent's `horizontalAlignment =
Alignment.CenterHorizontally` achieves that (the row fills available width, its internal content
left-aligns within it, consistent with how `Text(..., modifier = Modifier.fillMaxWidth())` is
already used elsewhere in this same composable's parent screens).

No changes needed to the private `FeatureItem` helper below it — unrelated.

### 3.5 `OnboardingRoute.kt` edits

**(a)** Add missing-permissions state next to the existing `permissionsDenied` state:

```kotlin
    var permissionsDenied by rememberSaveable { mutableStateOf(false) }
    var missingPermissions by rememberSaveable { mutableStateOf(setOf<String>()) }
```

**(b)** In the `permissionLauncher` callback, capture the already-computed `missing` set instead
of only logging it:

```kotlin
            } else {
                val missing = requiredPermissions - granted
                app.readylytics.health.domain.util.logD(
                    "OnboardingRoute",
                ) { "User denied some required permissions: $missing" }
                permissionsDenied = true
                missingPermissions = missing
                onPermissionsDenied()
            }
```

**(c)** Pass it through at the call site:

```kotlin
            if (permissionsDenied) {
                PermissionsRequiredScreen(
                    onGrantPermissionsClick = { permissionLauncher.launch(permissions) },
                    onOpenSettingsClick = {
                        val intent = Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
                        runCatching { context.startActivity(intent) }
                    },
                    missingPermissions = missingPermissions,
                )
```

`Set<String>` produced by `setOf<String>()`/`LinkedHashSet` (the runtime type of `requiredPermissions
- granted`) is `java.io.Serializable`, so it round-trips through Compose's default
`rememberSaveable` `Saver` the same way `permissionsDenied: Boolean` already does — no custom
`Saver` needed.

### 3.6 Bug fix: "Grant Permissions" button unreachable/no-op

**Root cause (confirmed by code review, high confidence):** `PermissionsRequiredScreen` is only
ever rendered from inside this block in `OnboardingRoute.kt` (§2.4, lines ~447–488):

```kotlin
if (skipToPermissions || isSyncing || isSyncError) {
    ...
    Surface(...) {
        if (permissionsDenied) {
            PermissionsRequiredScreen(...)
        } else { ... }
    }
    return
}

OnboardingScreen(...)   // <- primary flow (steps 0-3) falls through here
```

`permissionsDenied` is **not** part of the outer `if` condition. It is only consulted *after*
already being inside that branch. That branch is entered when `skipToPermissions` is true (restore
flow / already-onboarded user) or `isSyncing`/`isSyncError` is true — **never** merely because
`permissionsDenied` is true.

Walk through the **primary (non-restore) onboarding flow**, which is exactly the flow shown in the
screenshot that motivated screen 1's redesign (`RetentionSetupScreen`, step 3):

1. User finishes `ProfileSetupScreen` → `onProfileSetupComplete` fires in `OnboardingRoute.kt`,
   which sets `profileJustSaved = true` **before** saving (line 502, comment explains this is
   intentional to prevent a race). `profileJustSaved` is never set back to `false` anywhere in the
   file (confirmed via grep — it has exactly 2 occurrences: the `var` declaration and this one
   assignment).
2. User reaches `RetentionSetupScreen` (step 3) and taps "Grant Access & Continue" →
   `onRetentionSetupComplete` → `onboardingViewModel.saveRetention(...) { permissionLauncher.launch(permissions) }`
   — the system Health Connect permission dialog opens.
3. User denies one or more required permissions. The `permissionLauncher` callback sets
   `permissionsDenied = true` and calls `onPermissionsDenied()` (which flips the app's
   `SyncViewModel` state to `NeedsPermissions` — that state does not by itself change anything in
   `OnboardingRoute`'s local composition).
4. Back in `OnboardingRoute`, `skipToPermissions` is `userPrefs?.isBirthdayConfigured == true &&
   !profileJustSaved` — and `profileJustSaved` is `true` (step 1), so `skipToPermissions` is
   **permanently `false` for the rest of this session**. `isSyncing`/`isSyncError` are also both
   `false` (state is `NeedsPermissions`, neither `SyncingCatchUp` nor `Error`).
5. So `if (skipToPermissions || isSyncing || isSyncError)` evaluates **false**, and execution falls
   straight through to `OnboardingScreen(...)` — which is step-driven internal state that hasn't
   changed (still step 3) — re-rendering `RetentionSetupScreen`, **not** `PermissionsRequiredScreen`.
   `permissionsDenied = true` is now dead: nothing reads it again until/unless the user later goes
   through the *other* (`skipToPermissions`) path.

Net effect: in the primary onboarding flow, once a user denies Health Connect permissions, the app
silently loops them back to the retention screen with zero explanation of what went wrong, and the
`PermissionsRequiredScreen` + its "Grant Permissions" button are **unreachable** — code that looks
like a working retry affordance is actually dead for this path. (The screen *does* render
correctly today for the restore/"already configured" path, where `skipToPermissions` starts and
stays `true` — so QA/manual testing that only exercises the restore flow would see the screen
appear to work, while the far more common first-time flow silently breaks.)

**Fix:** include `permissionsDenied` directly in the outer gate, so the screen (and its button) are
reachable from *any* flow that can set it, not only the restore path:

```kotlin
    if (skipToPermissions || isSyncing || isSyncError || permissionsDenied) {
```

That one-line change is sufficient — the existing `if (permissionsDenied) { PermissionsRequiredScreen(...) } else { ... }` branch inside is already correct and needs no further edits. The
`LaunchedEffect(Unit) { if (skipToPermissions && !autoLaunchTriggered) { ... } }` auto-launch guard
is unaffected: it only fires when `skipToPermissions` is true, so entering the outer block via
`permissionsDenied` alone (primary flow) will **not** trigger an unwanted duplicate auto-relaunch —
only the user's explicit "Grant Permissions"/"Open Health Connect Settings" taps do anything, which
is the correct behavior.

Apply this alongside the §3.5 changes (same file, same `if` line — §3.5(a)/(b)/(c) plus this
one-line gate change together fully resolve the button.)

**Secondary hypothesis (needs on-device confirmation, not a code fix by itself):** even after the
button is reachable, if a user has already denied the *same* Health Connect permission set two or
more times, Android/Health Connect's permission-request activity can — like standard runtime
permissions after repeated denial — return immediately with the same still-denied set without ever
showing UI, which looks identical to "the button does nothing." This is a platform behavior, not a
code defect, and the existing "Open Health Connect Settings" `TextButton` (already wired to
`HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS`) is the correct fallback for it. To confirm
whether this is *also* happening: reproduce with `adb logcat | grep OnboardingRoute` visible — the
existing `logD` calls already log `"Permission result received. Granted: $granted"` on every
launcher callback. If tapping "Grant Permissions" a second time logs that line **within
milliseconds** and with no dialog ever appearing on screen, that confirms the OS is short-circuiting
the request; if so, consider tracking a denial counter and, after e.g. 2 consecutive denials of the
same permission, visually promoting "Open Health Connect Settings" (e.g. to a filled `Button`) over
the now-likely-inert "Grant Permissions" retry, with a short explanatory string. This part is
**out of scope for this plan's initial fix** — do the one-line §3.6 fix and re-test first, since
it may turn out to be the entire explanation for what was reported.

---

## 4. Files touched (summary)

| File | Change |
|---|---|
| `feature/onboarding/src/main/res/values/strings.xml` | Shorten `onboarding_hc_permissions_desc` to a lead-in line; add 6 new permission-label/section strings |
| `feature/onboarding/src/main/kotlin/.../PermissionBullets.kt` | **New file** — `PermissionBulletRow` composable + `healthPermissionLabelRes` mapper |
| `feature/onboarding/src/main/kotlin/.../RetentionSetupScreen.kt` | Bigger title/body styles; 4 fixed bullets instead of one sentence |
| `feature/onboarding/src/main/kotlin/.../OnboardingScreen.kt` | `PermissionsRequiredScreen` gains `missingPermissions` param + conditional bullet section |
| `feature/onboarding/src/main/kotlin/.../OnboardingRoute.kt` | Track `missingPermissions` state from the permission-launcher result; pass to `PermissionsRequiredScreen`; **add `permissionsDenied` to the outer `if` gate (§3.6) so the screen/button are reachable from the primary onboarding flow, not just the restore flow** |

No other modules, screens, or data flows change. This is UI-only — it does not touch Health
Connect ingestion, Room, or scoring, so `internal-docs/DATA_FLOW.md` does not need an update.

---

## 5. Verification

1. `./gradlew ktlintFormat && ./gradlew testDebugUnitTest` (mandatory pre-commit per project
   convention).
2. `./gradlew :feature:onboarding:assembleDebug` (or full `assembleDebug`) to confirm both screens
   compile with the new file, params, and imports.
3. `./gradlew lintRelease` after the above, per project convention, once all coding tasks are
   resolved.
4. Manual verification via `installDebug` + running the onboarding flow:
   - Retention screen: confirm the "Health Connect permissions" title and body text are visibly
     larger, and Sleep / Heart Rate / HRV / Exercise each render as their own bulleted line.
   - Missing-permissions screen: on the system Health Connect permission dialog, deny a subset of
     permissions (e.g. only allow Exercise, deny Sleep/Heart Rate/HRV/Steps/History) and confirm
     the "Permissions needed" screen lists exactly the denied ones as bullets — not a generic
     paragraph, and not all six regardless of what was actually denied.
   - Also confirm the case where the user denies *all* permissions renders all six bullets
     correctly labeled (including "Historical Data Access" for `READ_HEALTH_DATA_HISTORY`).
   - **Button-fix regression test (primary flow):** go through onboarding as a *fresh* install
     (not restore-from-backup) — Welcome → profile setup → retention screen → "Grant Access &
     Continue" — and deny the Health Connect permission request. Confirm `PermissionsRequiredScreen`
     now appears (previously it did not — see §3.6) and that tapping "Grant Permissions" reopens
     the system permission dialog. Grant on the second attempt and confirm the flow proceeds to
     sync as normal.
   - **Button-fix regression test (restore flow):** repeat via Restore-from-Backup to confirm the
     previously-working path still works unchanged.
   - If the button still appears inert on the *first* denial-then-retry in either flow after the
     §3.6 fix, capture `adb logcat | grep OnboardingRoute` while tapping and follow the secondary
     hypothesis in §3.6 (Health Connect short-circuiting repeat requests) as the next investigation
     step.

Do not uninstall the production/Play Store app (`app.readylytics.health`) during device testing —
use a debug-variant install per project rules.
