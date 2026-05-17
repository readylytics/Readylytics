# Design: Secure Password-Protected Backups

## Objective
Extend the local backup system to produce password-protected ZIP archives containing a complete snapshot of the app's database and settings. The password will be persisted securely and verifiable by the user.

## Proposed Approaches

### Approach 1: Zip4j + Encrypted DataStore (Recommended)
Use **Zip4j** for standard AES-256 ZIP encryption. Store the master password in the existing `UserPreferences` but ensure it is handled via the `EncryptionManager` (which uses Android Keystore + Tink).
*   **Pros:** Industry standard, highly compatible, high security for the persisted key.
*   **Cons:** Requires adding Zip4j dependency.

### Approach 2: SQLCipher Backup + manual Zip
Back up the database using SQLCipher's native encryption and then zip the settings separately.
*   **Pros:** Leverages existing SQLCipher setup.
*   **Cons:** Settings wouldn't be as easily protected as the DB; more complex restore flow (multiple passwords/keys).

---

## Detailed Design (Approach 1)

### 1. Data Storage & Security
*   **Master Password:** Stored in `user_preferences.proto` as `string backup_password_hash`.
*   **Actual Key:** The raw password will be encrypted using the existing `EncryptionManager` before being saved to the DataStore. It never exists as plaintext on disk.
*   **Library:** Add `net.lingala.zip4j:zip4j:2.11.5`.

### 2. Backup Flow (`LocalBackupManager`)
1.  Generate the full JSON snapshot (including **all** fields from `UserPreferences`).
2.  Write JSON to a temporary file.
3.  Use `Zip4j` to create a ZIP archive from the temp file.
4.  Apply `ZipParameters` with `EncryptionMethod.AES` and `AesKeyStrength.KEY_STRENGTH_256`.
5.  Protect with the decrypted Master Password.
6.  Move ZIP to the target directory and delete the temp JSON.

### 3. Restore Flow (`LocalRestoreManager`)
1.  User selects a ZIP.
2.  App attempts to open the ZIP using the saved Master Password.
3.  If it fails (wrong password), prompt the user for the password (in case they changed it).
4.  Extract JSON, validate schema, and apply database/settings restoration.

### 4. UI Components
*   **Password Setup:** A secure text field in the Backup settings.
*   **Password Verification:** A "Test Password" feature where the user enters a string, and the UI provides immediate feedback ("Matches current master password" vs "Does not match").
*   **Password Visibility:** Standard eye-icon toggle to show/hide the password during entry.

### 5. Settings Completeness
Update `toDomainModel` and `toProto` in `UserPreferences.kt` and `UserPreferencesSerializer.kt` to ensure every single preference is mapped, including:
*   TRIMP parameters (`banisterMultiplier`, `chengBeta`, `itrimB`).
*   HR Zones and custom thresholds.
*   App appearance settings.
*   Backup URI and schedules.

## Verification Plan
*   **Unit Tests:** Mock `EncryptionManager` to verify password round-tripping. Verify `LocalBackupManager` produces a valid ZIP.
*   **Manual Test:** Create a backup, change a setting (e.g., Theme), restore from backup, and verify the setting reverts. Verify "Test Password" UI correctly identifies wrong inputs.
