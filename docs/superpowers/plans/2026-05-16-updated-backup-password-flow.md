# Plan: Updated Backup Password Flow

## Objective
Refactor the local backup password flow to use a popup dialog for setting/changing passwords and ensure a seamless backup initiation after setting the password.

## Key Files & Context
*   `app/src/main/java/com/gregor/lauritz/healthdashboard/ui/settings/SettingsState.kt`
*   `app/src/main/java/com/gregor/lauritz/healthdashboard/ui/settings/SettingsEvent.kt`
*   `app/src/main/java/com/gregor/lauritz/healthdashboard/ui/settings/LocalBackupViewModel.kt`
*   `app/src/main/java/com/gregor/lauritz/healthdashboard/ui/settings/backup/LocalBackupSettings.kt`

## Implementation Steps

### Task 1: State & Event Updates
- [ ] **Step 1: Update `LocalBackupState`**
    *   Add `isPasswordSet: Boolean = false`.
    *   Add `showSetPasswordDialog: Boolean = false`.
- [ ] **Step 2: Update `SettingsEvent`**
    *   Add `OpenSetPasswordDialog`.
    *   Add `DismissSetPasswordDialog`.
    *   Modify `UpdateBackupPassword` to include an `autoStartBackup: Boolean` flag.

### Task 2: ViewModel Refactoring
- [ ] **Step 1: Update `uiState` mapping in `LocalBackupViewModel`**
    *   Calculate `isPasswordSet` based on `prefs.backupPasswordHash != null`.
    *   Pass `showSetPasswordDialog` from transient state.
- [ ] **Step 2: Refactor `CreateLocalBackup` event handling**
    *   Check `isPasswordSet`. If false, update transient state to set `showSetPasswordDialog = true` and return.
- [ ] **Step 3: Handle new events**
    *   `OpenSetPasswordDialog`: Set `showSetPasswordDialog = true`.
    *   `DismissSetPasswordDialog`: Set `showSetPasswordDialog = false`.
    *   `UpdateBackupPassword`: If `autoStartBackup` is true, call the private backup logic after saving the new hash.

### Task 3: UI Implementation
- [ ] **Step 1: Create `SetPasswordDialog` in `LocalBackupSettings.kt`**
    *   Fields: "New Password" and "Repeat Password".
    *   Validation: Must match and be non-empty.
    *   Actions: "Cancel" and "Save & Backup" (or just "Save" if triggered from Change Password).
- [ ] **Step 2: Update `LocalBackupSection`**
    *   Display `SetPasswordDialog` based on `uiState.showSetPasswordDialog`.
- [ ] **Step 3: Update `BackupPasswordSection`**
    *   Remove the "Set Master Password" `OutlinedTextField`.
    *   Add a "Change Backup Password" button.
    *   Retain "Test Backup Password" for verification.

## Verification & Testing
*   **Initial Flow:** Click "Create Backup" with no password set. Verify popup appears. Enter matching passwords. Verify backup starts immediately after clicking "Save & Backup".
*   **Change Flow:** Click "Change Password". Verify popup appears. Change password. Verify "Test Password" correctly identifies the new password and rejects the old one.
*   **Validation:** Verify that mismatched passwords in the dialog prevent saving.
