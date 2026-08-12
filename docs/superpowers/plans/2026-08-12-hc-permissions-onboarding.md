# HC-Permissions Onboarding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Update the HC permissions onboarding UI with larger text, bulleted lists, dynamic missing permission reporting, and fix a reachability bug in the retry screen.

**Architecture:** UI updates in Compose. State flows from `PermissionController` launcher result down to the `PermissionsRequiredScreen` via `OnboardingRoute.kt`.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, Android Health Connect API.

## Global Constraints

- Must run `./gradlew ktlintFormat && ./gradlew testDebugUnitTest` before completing work.
- Must compile successfully with `./gradlew :feature:onboarding:assembleDebug`.
- Follow strict M3 design conventions.

---

### Task 1: Update String Resources

**Files:**
- Modify: `feature/onboarding/src/main/res/values/strings.xml`

**Interfaces:**
- Produces: New string resources for UI components (`onboarding_hc_permission_sleep`, etc.)

- [ ] **Step 1: Update strings.xml**

Replace `onboarding_hc_permissions_desc` and add new strings in `feature/onboarding/src/main/res/values/strings.xml`:

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

- [ ] **Step 2: Commit**

```bash
rtk git add feature/onboarding/src/main/res/values/strings.xml
rtk git commit -m "feat(onboarding): Update HC permission string resources"
```

### Task 2: Create PermissionBullets Component

**Files:**
- Create: `feature/onboarding/src/main/kotlin/app/readylytics/health/feature/onboarding/PermissionBullets.kt`

**Interfaces:**
- Consumes: `onboarding_hc_permission_*` strings from Task 1
- Produces: `@Composable internal fun PermissionBulletRow(text: String, modifier: Modifier = Modifier)` and `@StringRes internal fun healthPermissionLabelRes(permission: String): Int?`

- [ ] **Step 1: Write `PermissionBullets.kt`**

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

- [ ] **Step 2: Run format check**

Run: `./gradlew ktlintFormat`
Expected: SUCCESS

- [ ] **Step 3: Commit**

```bash
rtk git add feature/onboarding/src/main/kotlin/app/readylytics/health/feature/onboarding/PermissionBullets.kt
rtk git commit -m "feat(onboarding): Add PermissionBullets UI component"
```

### Task 3: Update RetentionSetupScreen

**Files:**
- Modify: `feature/onboarding/src/main/kotlin/app/readylytics/health/feature/onboarding/RetentionSetupScreen.kt`

**Interfaces:**
- Consumes: `PermissionBulletRow` from Task 2.

- [ ] **Step 1: Update UI styles and replace prose with bullets**

In `RetentionSetupScreen.kt`, replace the existing `Text` components for `onboarding_hc_permissions_label` and `onboarding_hc_permissions_desc` with the following:

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

- [ ] **Step 2: Check compilation**

Run: `./gradlew :feature:onboarding:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
rtk git add feature/onboarding/src/main/kotlin/app/readylytics/health/feature/onboarding/RetentionSetupScreen.kt
rtk git commit -m "feat(onboarding): Add bullet points and increase text size on retention screen"
```

### Task 4: Update PermissionsRequiredScreen

**Files:**
- Modify: `feature/onboarding/src/main/kotlin/app/readylytics/health/feature/onboarding/OnboardingScreen.kt`

**Interfaces:**
- Consumes: `PermissionBulletRow` and `healthPermissionLabelRes` from Task 2.
- Produces: Updated `PermissionsRequiredScreen` signature accepting `missingPermissions: Set<String>`.

- [ ] **Step 1: Update signature and add dynamic bullets**

In `OnboardingScreen.kt`, replace `PermissionsRequiredScreen` completely:

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

- [ ] **Step 2: Check compilation**

Run: `./gradlew :feature:onboarding:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
rtk git add feature/onboarding/src/main/kotlin/app/readylytics/health/feature/onboarding/OnboardingScreen.kt
rtk git commit -m "feat(onboarding): Add dynamic missing permissions list to PermissionsRequiredScreen"
```

### Task 5: State Routing & Bug Fix

**Files:**
- Modify: `feature/onboarding/src/main/kotlin/app/readylytics/health/feature/onboarding/OnboardingRoute.kt`

**Interfaces:**
- Consumes: Updated `PermissionsRequiredScreen` from Task 4.

- [ ] **Step 1: Add state for missingPermissions**

In `OnboardingRoute.kt`, immediately below `var permissionsDenied by rememberSaveable { mutableStateOf(false) }`:

```kotlin
    var missingPermissions by rememberSaveable { mutableStateOf(setOf<String>()) }
```

- [ ] **Step 2: Capture missing permissions**

In the `permissionLauncher` `else` block:

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

- [ ] **Step 3: Fix outer routing gate and pass state**

Find the `if (skipToPermissions || isSyncing || isSyncError)` block and update it to:

```kotlin
    if (skipToPermissions || isSyncing || isSyncError || permissionsDenied) {
```

Then, update the `PermissionsRequiredScreen` call site to pass the state:

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

- [ ] **Step 4: Full validation build**

Run: `./gradlew ktlintFormat && ./gradlew testDebugUnitTest`
Expected: SUCCESS

Run: `./gradlew :feature:onboarding:assembleDebug`
Expected: SUCCESS

- [ ] **Step 5: Commit**

```bash
rtk git add feature/onboarding/src/main/kotlin/app/readylytics/health/feature/onboarding/OnboardingRoute.kt
rtk git commit -m "fix(onboarding): Pass missing permissions state and fix retry screen reachability"
```
