# Design: HC-Permissions Onboarding & Bug Fix

## 1. Overview
Update the Health Connect permissions onboarding UI to use larger text and explicit bulleted lists for better scannability. Additionally, dynamically display the exact missing permissions when denied, and fix a bug where the "Permissions Required" screen was unreachable in the primary onboarding flow.

## 2. Architecture & Data Flow
- **State Management**: `OnboardingRoute.kt` captures missing permissions from the Android `PermissionController` and passes them down as UI state (`missingPermissions: Set<String>`).
- **UI Components**: A new `PermissionBullets.kt` component maps Health Connect string identifiers to localized string resources to render consistent `PermissionBulletRow` elements across the flow.
- **Routing**: The primary onboarding flow condition is updated to observe `permissionsDenied` state so the retry screen can be rendered.

## 3. Components
- **Strings (`strings.xml`)**: Add specific strings for each HC permission and the missing permissions label. Update the description string to serve as a lead-in.
- **`PermissionBullets.kt`**: Contains `PermissionBulletRow` composable and `healthPermissionLabelRes(permission: String): Int?`.
- **`RetentionSetupScreen.kt`**: Uses `titleMedium` / `bodyMedium` and hardcodes the 4 required permissions (Sleep, HR, HRV, Exercise) as bullets.
- **`OnboardingScreen.kt` (`PermissionsRequiredScreen`)**: Receives `missingPermissions`, maps them to string resources, and renders them dynamically as bullets.
- **`OnboardingRoute.kt`**: Tracks `missingPermissions` state, passes it down, and updates the conditional routing gate (`if (skipToPermissions || isSyncing || isSyncError || permissionsDenied)`).

## 4. Error Handling & Edge Cases
- All required permissions defined in `HealthConnectRepositoryImpl` must have a mapping in `healthPermissionLabelRes` to ensure no denied permission is silently omitted from the UI list. (Specifically includes `READ_HEALTH_DATA_HISTORY`).
- Secondary hypothesis regarding OS short-circuiting of repeated denial dialogs is deferred to a future iteration if observed in testing.

## 5. Testing
- Run unit tests and format checks (`./gradlew ktlintFormat && ./gradlew testDebugUnitTest`).
- Verify visually on device for both `RetentionSetupScreen` and `PermissionsRequiredScreen` (denying some vs all permissions).
- Regression test primary flow vs restore flow to ensure the "Grant Permissions" button is reachable and functional in both.
