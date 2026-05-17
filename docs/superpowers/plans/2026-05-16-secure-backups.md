# Secure Password-Protected Backups Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the local backup system to produce AES-256 password-protected ZIP archives containing complete app data (database and settings), with secure master password storage.

**Architecture:** 
1.  **Encryption:** Use `Zip4j` for ZIP creation and AES-256 encryption.
2.  **Security:** Persist the master password in DataStore, encrypted via Android Keystore/Tink through the existing `EncryptionManager`.
3.  **Completeness:** Update serialization to include every app preference in the backup snapshot.
4.  **UI:** Add password management and a "Test Password" verification tool in Settings.

**Tech Stack:** Zip4j (v2.11.5), DataStore (Protobuf), Android Keystore/Tink, Jetpack Compose.

---

### Task 1: Dependency & Proto Setup

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/proto/user_preferences.proto`

- [ ] **Step 1: Add Zip4j to version catalog**
```toml
# gradle/libs.versions.toml
[versions]
# ...
zip4j = "2.11.5"

[libraries]
# ...
zip4j = { group = "net.lingala.zip4j", name = "zip4j", version.ref = "zip4j" }
```

- [ ] **Step 2: Add Zip4j dependency to app module**
```kotlin
// app/build.gradle.kts
dependencies {
    // ...
    implementation(libs.zip4j)
}
```

- [ ] **Step 3: Add backup_password_hash to proto**
```proto
// app/src/main/proto/user_preferences.proto
message UserPreferencesProto {
    // ...
    oneof backup_password_hash_oneof {
        string backup_password_hash = 58;
    }
}
```

- [ ] **Step 4: Verify compilation**
Run: `./gradlew compileDebugKotlin`
Expected: PASS

- [ ] **Step 5: Commit**
```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/proto/user_preferences.proto
git commit -m "chore: add zip4j dependency and update preferences proto"
```

---

### Task 2: Secure Password Storage Implementation

**Files:**
- Modify: `app/src/main/java/com/gregor/lauritz/healthdashboard/data/preferences/UserPreferences.kt`
- Modify: `app/src/main/java/com/gregor/lauritz/healthdashboard/data/preferences/BackupPreferences.kt`
- Modify: `app/src/main/java/com/gregor/lauritz/healthdashboard/data/preferences/SettingsRepository.kt`
- Modify: `app/src/main/java/com/gregor/lauritz/healthdashboard/data/preferences/UserPreferencesSerializer.kt`

- [ ] **Step 1: Add password field to domain model**
```kotlin
// app/src/main/java/com/gregor/lauritz/healthdashboard/data/preferences/UserPreferences.kt
data class UserPreferences(
    // ...
    val backupPasswordHash: String? = null,
)

// Update toDomainModel
fun UserPreferencesProto.toDomainModel(): UserPreferences {
    return UserPreferences(
        // ...
        backupPasswordHash = if (hasBackupPasswordHash()) backupPasswordHash else null,
    )
}
```

- [ ] **Step 2: Update BackupPreferences**
```kotlin
// app/src/main/java/com/gregor/lauritz/healthdashboard/data/preferences/BackupPreferences.kt
internal class BackupPreferences @Inject constructor(...) {
    // ...
    suspend fun updateBackupPasswordHash(hash: String?) {
        dataStore.updateData { builder ->
            if (hash != null) {
                builder.toBuilder().setBackupPasswordHash(hash).build()
            } else {
                builder.toBuilder().clearBackupPasswordHash().build()
            }
        }
    }
}
```

- [ ] **Step 3: Update SettingsRepository**
```kotlin
// app/src/main/java/com/gregor/lauritz/healthdashboard/data/preferences/SettingsRepository.kt
class SettingsRepository @Inject constructor(...) {
    // ...
    suspend fun updateBackupPasswordHash(hash: String?) = backup.updateBackupPasswordHash(hash)
}
```

- [ ] **Step 4: Update UserPreferencesSerializer.toProto**
```kotlin
// app/src/main/java/com/gregor/lauritz/healthdashboard/data/preferences/UserPreferencesSerializer.kt
fun UserPreferences.toProto(): UserPreferencesProto {
    val builder = UserPreferencesProto.newBuilder()
    // ...
    domain.backupPasswordHash?.let { builder.setBackupPasswordHash(it) }
    return builder.build()
}
```

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/gregor/lauritz/healthdashboard/data/preferences/
git commit -m "feat: implement secure storage for backup password"
```

---

### Task 3: Comprehensive Settings Backup

**Files:**
- Modify: `app/src/main/java/com/gregor/lauritz/healthdashboard/domain/backup/LocalBackupManager.kt`
- Modify: `app/src/main/java/com/gregor/lauritz/healthdashboard/domain/backup/LocalRestoreManager.kt`

- [ ] **Step 1: Update writePreferences to include ALL fields**
```kotlin
// app/src/main/java/com/gregor/lauritz/healthdashboard/domain/backup/LocalBackupManager.kt
private suspend fun writePreferences(writer: JsonWriter) {
    val prefs = settingsRepository.userPreferences.first()
    writer.beginObject()
    // ENSURE ALL FIELDS ARE HERE:
    writer.name("goalSleepHours").value(prefs.goalSleepHours.toDouble())
    writer.name("maxHeartRate").value(prefs.maxHeartRate.toLong())
    writer.name("autoCalculateMaxHr").value(prefs.autoCalculateMaxHr)
    writer.name("trimpModel").value(prefs.trimpModel.name)
    writer.name("banisterMultiplier").value(prefs.banisterMultiplier.toDouble())
    writer.name("chengBeta").value(prefs.chengBeta.toDouble())
    writer.name("itrimB").value(prefs.itrimB.toDouble())
    writer.name("stepGoal").value(prefs.stepGoal.toLong())
    writer.name("appTheme").value(prefs.appTheme.name)
    writer.name("dynamicColorEnabled").value(prefs.dynamicColorEnabled)
    // ... add remaining fields from UserPreferences.kt
    writer.endObject()
}
```

- [ ] **Step 2: Update restorePreferences to handle ALL fields**
```kotlin
// app/src/main/java/com/gregor/lauritz/healthdashboard/domain/backup/LocalRestoreManager.kt
private suspend fun restorePreferences(json: JSONObject) {
    // Symmetric to Task 3 Step 1
    if (json.has("trimpModel")) {
        settingsRepository.updateTrimpModel(TrimpModel.valueOf(json.getString("trimpModel")))
    }
    // ... update all other fields via settingsRepository
}
```

- [ ] **Step 3: Commit**
```bash
git add app/src/main/java/com/gregor/lauritz/healthdashboard/domain/backup/
git commit -m "feat: include all app settings in backup snapshot"
```

---

### Task 4: Password-Protected ZIP Implementation

**Files:**
- Modify: `app/src/main/java/com/gregor/lauritz/healthdashboard/domain/backup/LocalBackupManager.kt`
- Modify: `app/src/main/java/com/gregor/lauritz/healthdashboard/domain/backup/LocalRestoreManager.kt`

- [ ] **Step 1: Inject EncryptionManager in Managers**
Update constructors of `LocalBackupManager` and `LocalRestoreManager` to include `private val encryptionManager: EncryptionManager`.

- [ ] **Step 2: Refactor LocalBackupManager.createBackup to use Zip4j**
```kotlin
// app/src/main/java/com/gregor/lauritz/healthdashboard/domain/backup/LocalBackupManager.kt
// 1. Write JSON to temp file
// 2. Get master password from prefs and decrypt it
val password = prefs.backupPasswordHash?.let { encryptionManager.decrypt(it) } ?: ""
// 3. Create ZIP using Zip4j
val zipFile = ZipFile(finalFile, password.toCharArray())
val zipParameters = ZipParameters().apply {
    isEncryptFiles = true
    encryptionMethod = EncryptionMethod.AES
    aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
}
zipFile.addFile(tempJsonFile, zipParameters)
```

- [ ] **Step 3: Refactor LocalRestoreManager to extract ZIP**
```kotlin
// app/src/main/java/com/gregor/lauritz/healthdashboard/domain/backup/LocalRestoreManager.kt
// 1. Try to open ZIP with saved password
// 2. If fails, throw custom Exception (needs user prompt)
// 3. Extract to temp dir, read JSON, proceed
```

- [ ] **Step 4: Commit**
```bash
git commit -m "feat: implement AES-256 ZIP protection for backups"
```

---

### Task 5: UI & Password Verification

**Files:**
- Modify: `app/src/main/java/com/gregor/lauritz/healthdashboard/ui/settings/SettingsEvent.kt`
- Modify: `app/src/main/java/com/gregor/lauritz/healthdashboard/ui/settings/LocalBackupViewModel.kt`
- Modify: `app/src/main/java/com/gregor/lauritz/healthdashboard/ui/settings/backup/LocalBackupSettings.kt`

- [ ] **Step 1: Add Password Events**
```kotlin
// SettingsEvent.kt
data class UpdateBackupPassword(val raw: String) : SettingsEvent
data class VerifyBackupPassword(val test: String) : SettingsEvent
```

- [ ] **Step 2: Update ViewModel Logic**
Implement `UpdateBackupPassword` (encrypts and saves) and `VerifyBackupPassword` (checks input against decrypted saved password).

- [ ] **Step 3: Implement Password UI Section**
Add `OutlinedTextField` for setting password and a "Test Your Password" section with success/failure feedback in `LocalBackupSettings.kt`.

- [ ] **Step 4: Verify build and tests**
Run: `./gradlew testDebugUnitTest`
Expected: PASS

- [ ] **Step 5: Final Commit**
```bash
git add app/src/main/java/com/gregor/lauritz/healthdashboard/
git commit -m "feat: add password management and verification UI"
```
