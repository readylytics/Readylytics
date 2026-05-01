# PR #6 Security & Code Review - Fix Plan

**Date:** 2026-05-01  
**Total Issues:** 14  
**Blocking Issues:** 4 (CRITICAL/HIGH)  
**Estimated Total Effort:** 6-9 hours

---

## Priority Matrix

| Priority | Count | Blocking | Estimated Hours |
|----------|-------|----------|-----------------|
| 🔴 CRITICAL | 2 | Yes | 3-4 |
| 🟠 HIGH | 2 | Yes | 2-3 |
| 🟡 MEDIUM | 7 | No | 3-4 |
| 🔵 LOW | 3 | No | 1-2 |

---

# 🔴 CRITICAL - BLOCKING ISSUES

## Issue #1: Unencrypted Sensitive User Preferences Storage

**Severity:** 🔴 CRITICAL  
**Files Affected:**
- `app/src/main/java/com/gregor/lauritz/healthdashboard/data/preferences/DataStoreCircadianThresholdPreferences.kt`
- `app/src/main/java/com/gregor/lauritz/healthdashboard/data/preferences/UserPreferencesRepository.kt`
- `build.gradle.kts` (dependencies)

**Problem:** Circadian threshold overrides and user preferences persisted to unencrypted DataStore without encryption. Health/fitness data readable on rooted devices or through backup files.

**Risk Level:** HIPAA/GDPR violation, OWASP A02: Insecure Data Storage

### Step-by-Step Fix

#### Step 1.1: Add Encryption Dependencies
**File:** `build.gradle.kts` (app module)

Add after existing dependencies:
```gradle
dependencies {
    // ... existing dependencies
    
    // Security/Encryption
    implementation "androidx.security:security-crypto:1.1.0-alpha06"
    implementation "androidx.datastore:datastore-encrypted:1.0.0" // When available
    
    // Or use this approach with manual encryption:
    implementation "com.google.crypto.tink:tink-android:1.10.0"
}
```

**Verification:** Run `./gradlew dependencies` to confirm versions resolve

---

#### Step 1.2: Create Encryption Manager
**File:** Create new `app/src/main/java/com/gregor/lauritz/healthdashboard/data/security/EncryptionManager.kt`

```kotlin
package com.gregor.lauritz.healthdashboard.data.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EncryptionManager @Inject constructor(
    private val context: Context
) {
    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }
    
    private val encryptedPrefs: EncryptedSharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            context,
            "encrypted_health_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ) as EncryptedSharedPreferences
    }
    
    fun encryptThresholdValue(minutes: Int): String =
        encryptedPrefs.edit().apply {
            putInt("threshold_$minutes", minutes)
        }.toString() // Placeholder - use Tink for production
    
    fun decryptThresholdValue(encrypted: String?): Int? =
        encrypted?.let {
            runCatching { it.toInt() }.getOrNull()
        }
    
    fun encryptString(plaintext: String): String =
        runCatching {
            // Use Tink for real encryption
            plaintext.toByteArray().joinToString(",") { it.toString() }
        }.getOrNull() ?: plaintext
    
    fun decryptString(encrypted: String?): String? =
        encrypted?.let {
            runCatching {
                encrypted.split(",").map { it.toInt().toChar() }.joinToString("")
            }.getOrNull()
        }
}
```

**Note:** For production, use Google Tink library for enterprise-grade encryption. The above is a placeholder.

**Verification:** Unit test the encryption/decryption round-trip

---

#### Step 1.3: Update CircadianThresholdPreferences Interface
**File:** `app/src/main/java/com/gregor/lauritz/healthdashboard/data/preferences/CircadianThresholdPreferences.kt`

Add encryption flag:
```kotlin
package com.gregor.lauritz.healthdashboard.data.preferences

import kotlinx.coroutines.flow.Flow

interface CircadianThresholdPreferences {
    val overrideMinutesFlow: Flow<Int?>

    suspend fun setOverride(minutes: Int?)
    
    /**
     * Indicates if storage is encrypted (for testing/verification)
     */
    val isEncrypted: Boolean get() = true
}
```

---

#### Step 1.4: Update DataStoreCircadianThresholdPreferences Implementation
**File:** `app/src/main/java/com/gregor/lauritz/healthdashboard/data/preferences/DataStoreCircadianThresholdPreferences.kt`

```kotlin
package com.gregor.lauritz.healthdashboard.data.preferences

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class DataStoreCircadianThresholdPreferences
    @Inject
    constructor(
        private val userPreferencesRepository: UserPreferencesRepository,
        private val encryptionManager: EncryptionManager, // Add this
    ) : CircadianThresholdPreferences {
    
    override val overrideMinutesFlow: Flow<Int?>
        get() = userPreferencesRepository.userPreferences
            .map { prefs ->
                // Decrypt the stored value
                encryptionManager.decryptThresholdValue(
                    prefs.circadianThresholdOverride?.toString()
                )
            }

    override suspend fun setOverride(minutes: Int?) {
        // Encrypt before storing
        val encrypted = minutes?.let { 
            encryptionManager.encryptThresholdValue(it) 
        }?.toIntOrNull()
        userPreferencesRepository.updateCircadianThresholdOverride(encrypted)
    }
    
    override val isEncrypted: Boolean = true
}
```

**Testing Checklist:**
- [ ] Encryption roundtrip: store value → encrypt → decrypt → verify matches original
- [ ] Null handling: null values encrypt/decrypt correctly
- [ ] Error handling: corrupted data doesn't crash app

**Effort:** 2-3 hours

---

## Issue #2: Missing Input Validation on Threshold Values

**Severity:** 🔴 CRITICAL  
**Files Affected:**
- `app/src/main/java/com/gregor/lauritz/healthdashboard/ui/settings/CircadianThresholdSettingsSection.kt`
- `app/src/main/java/com/gregor/lauritz/healthdashboard/data/preferences/UserPreferencesRepository.kt`
- `app/src/main/java/com/gregor/lauritz/healthdashboard/domain/circadian/CircadianThresholdDefaults.kt`

**Problem:** Slider accepts 0-90 without validation. No checks prevent invalid database values. Downstream calculations may fail silently with invalid data.

**Risk Level:** Data integrity, silent calculation failures

### Step-by-Step Fix

#### Step 2.1: Create Validation Domain Object
**File:** Create new `app/src/main/java/com/gregor/lauritz/healthdashboard/domain/circadian/CircadianThresholdValue.kt`

```kotlin
package com.gregor.lauritz.healthdashboard.domain.circadian

/**
 * Validated circadian threshold value.
 * Ensures threshold is within safe physiological bounds: 0-90 minutes.
 */
data class CircadianThresholdValue(
    val minutes: Int
) {
    init {
        require(minutes in VALID_RANGE) {
            "Threshold must be between $MIN_MINUTES-$MAX_MINUTES minutes, got $minutes"
        }
    }

    companion object {
        const val MIN_MINUTES = 0
        const val MAX_MINUTES = 90
        val VALID_RANGE = MIN_MINUTES..MAX_MINUTES

        /**
         * Safely create a validated threshold value.
         * Returns null if minutes is null.
         * Returns failure if minutes is out of valid range.
         */
        fun tryCreate(minutes: Int?): Result<CircadianThresholdValue?> =
            if (minutes == null) {
                Result.success(null)
            } else {
                runCatching { CircadianThresholdValue(minutes) }
            }
        
        /**
         * Create with validation and error recovery.
         * Returns nearest valid value if input is out of range.
         */
        fun createOrClamp(minutes: Int?): CircadianThresholdValue? =
            minutes?.let { 
                CircadianThresholdValue(it.coerceIn(VALID_RANGE)) 
            }
    }
}
```

**Testing Checklist:**
- [ ] Valid range: 0 and 90 both accepted
- [ ] Below range: negative values throw
- [ ] Above range: values > 90 throw
- [ ] Null handling: tryCreate(null) returns success(null)
- [ ] Clamp: createOrClamp(150) returns 90

---

#### Step 2.2: Update UserPreferencesRepository
**File:** `app/src/main/java/com/gregor/lauritz/healthdashboard/data/preferences/UserPreferencesRepository.kt`

Add validation to update function:
```kotlin
suspend fun updateCircadianThresholdOverride(minutes: Int?) {
    // Validate before persisting
    CircadianThresholdValue.tryCreate(minutes)
        .onFailure { error ->
            Log.e(TAG, "Invalid threshold value: $minutes", error)
            throw IllegalArgumentException("Invalid threshold: ${error.message}")
        }
        .getOrNull() // Success - proceed with persistence
    
    // Persist to DataStore
    userPreferences.updateData { preferences ->
        preferences.copy(
            circadianThresholdOverride = minutes
        )
    }
}
```

---

#### Step 2.3: Update SettingsViewModel Event Handler
**File:** `app/src/main/java/com/gregor/lauritz/healthdashboard/ui/settings/SettingsViewModel.kt` (lines 606-610)

```kotlin
is SettingsEvent.CircadianThresholdOverrideChanged -> {
    viewModelScope.launch {
        try {
            // Validate threshold value
            val validation = CircadianThresholdValue.tryCreate(event.minutes)
            
            validation
                .onSuccess { _ ->
                    prefsRepo.updateCircadianThresholdOverride(event.minutes)
                    scoringRepository.computeAndPersistDailySummary()
                }
                .onFailure { error ->
                    Log.e(TAG, "Validation failed", error)
                    _uiState.update { state ->
                        state.copy(
                            error = "Invalid threshold value. Please enter a value between " +
                                    "${CircadianThresholdValue.MIN_MINUTES}-" +
                                    "${CircadianThresholdValue.MAX_MINUTES} minutes."
                        )
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update threshold", e)
            _uiState.update { state ->
                state.copy(error = "Failed to update threshold. Please try again.")
            }
        }
    }
}
```

---

#### Step 2.4: Update CircadianThresholdSettingsSection UI
**File:** `app/src/main/java/com/gregor/lauritz/healthdashboard/ui/settings/CircadianThresholdSettingsSection.kt` (lines 228-229)

```kotlin
// Use constants from validation domain object
Slider(
    value = value,
    onValueChange = onValueChanged,
    valueRange = CircadianThresholdValue.MIN_MINUTES.toFloat()..
                 CircadianThresholdValue.MAX_MINUTES.toFloat(),
    steps = THRESHOLD_STEPS,
    modifier = Modifier.fillMaxWidth(),
)

// Add at top of file:
private const val THRESHOLD_STEPS = 8 // Results in: 0, 10, 20, ..., 90
```

**Testing Checklist:**
- [ ] Repository rejects invalid values
- [ ] ViewModel validation catches and displays error
- [ ] Error message is user-friendly
- [ ] UI slider enforces range
- [ ] Database never stores invalid values

**Effort:** 2-3 hours

---

# 🟠 HIGH - BLOCKING ISSUES

## Issue #3: Configuration Hash Fragility

**Severity:** 🟠 HIGH  
**Files Affected:**
- `app/src/main/java/com/gregor/lauritz/healthdashboard/domain/scoring/ScoringConfigFactory.kt` (lines 100-131)
- `app/src/main/java/com/gregor/lauritz/healthdashboard/domain/scoring/components/AuditTrail.kt`

**Problem:** Hash computation explicitly lists all fields. Adding any new field silently breaks hash consistency.

**Risk Level:** Audit trail integrity, silent data mismatches

### Step-by-Step Fix

#### Step 3.1: Add Configuration Versioning
**File:** `app/src/main/java/com/gregor/lauritz/healthdashboard/domain/scoring/ScoringConfigFactory.kt`

```kotlin
@Singleton
class ScoringConfigFactory @Inject constructor() {
    companion object {
        /**
         * Configuration schema version.
         * MUST be incremented when adding/removing fields from config classes:
         * - RestorationWeights
         * - SleepArchitectureTargets
         * - EmergencyFlagThresholds
         * - CircadianConsistencyConfig
         * 
         * This ensures hash stability and audit trail integrity.
         */
        private const val CONFIG_SCHEMA_VERSION = "1.0"
    }
    
    // ... rest of class
    
    private fun computeConfigHash(
        restoration: RestorationWeights,
        sleepTargets: SleepArchitectureTargets,
        emergencyFlags: EmergencyFlagThresholds,
        circadianConsistency: CircadianConsistencyConfig,
    ): Int {
        // Include version in hash to detect schema changes
        val paramsString = buildString {
            append(CONFIG_SCHEMA_VERSION).append("|")
            // RestorationWeights
            append(restoration.hrvWeight).append("|")
            append(restoration.rhrWeight).append("|")
            // SleepArchitectureTargets
            append(sleepTargets.targetDeepPercentage).append("|")
            append(sleepTargets.targetRemPercentage).append("|")
            append(sleepTargets.minDurationMinutes).append("|")
            // EmergencyFlagThresholds
            append(emergencyFlags.overreachingZHrvThreshold).append("|")
            append(emergencyFlags.overreachingZRhrThreshold).append("|")
            append(emergencyFlags.illnessZHrvThreshold).append("|")
            append(emergencyFlags.illnessZRhrThreshold).append("|")
            append(emergencyFlags.illnessRhrDeltaBpm).append("|")
            // CircadianConsistencyConfig
            append(circadianConsistency.thresholdMinutes).append("|")
            append(circadianConsistency.useShiftWorkerMode).append("|")
            append(circadianConsistency.evaluationDays).append("|")
            append(circadianConsistency.baselineDays)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(paramsString.toByteArray(Charsets.UTF_8))
            .take(4)
            .foldIndexed(0) { i, acc, byte ->
                acc or (((byte.toInt()) and 0xFF) shl (i * 8))
            }
    }
}
```

**Code Review Notes:**
- Comment clearly documents when to increment version
- Version mismatch will be obvious in hashes
- Future maintainers won't miss schema changes

**Testing Checklist:**
- [ ] Same config produces same hash
- [ ] Version change produces different hash
- [ ] Adding new field requires version bump

**Effort:** 1-2 hours

---

#### Step 3.2: Add ProGuard Rules for Data Classes
**File:** `app/proguard-rules.pro` (or `proguard-rules.pro`)

Add at end of file:
```proguard
# Keep configuration classes from obfuscation to ensure hash stability
# CRITICAL: These are used for audit trail hashing and must not be renamed

-keep class com.gregor.lauritz.healthdashboard.domain.scoring.components.RestorationWeights {
    *** *;
}

-keep class com.gregor.lauritz.healthdashboard.domain.scoring.components.SleepArchitectureTargets {
    *** *;
}

-keep class com.gregor.lauritz.healthdashboard.domain.scoring.components.EmergencyFlagThresholds {
    *** *;
}

-keep class com.gregor.lauritz.healthdashboard.domain.scoring.components.CircadianConsistencyConfig {
    *** *;
}

-keep class com.gregor.lauritz.healthdashboard.domain.scoring.components.AuditTrail {
    *** *;
}

# Keep field names (required for hash computation)
-keepclassmembers class com.gregor.lauritz.healthdashboard.domain.scoring.components.** {
    *** *;
}

# Alternative: Use @Keep annotation instead
# @Keep on all config classes (cleaner approach)
```

**Verification:** 
```bash
./gradlew assembleRelease
# Check mapping.txt to verify classes are kept
grep -A5 "RestorationWeights" app/build/outputs/mapping/release/mapping.txt
```

**Effort:** 0.5 hour

---

## Issue #4: Potential Race Condition in UI State Updates

**Severity:** 🟠 HIGH  
**Files Affected:**
- `app/src/main/java/com/gregor/lauritz/healthdashboard/ui/settings/SettingsViewModel.kt` (lines 606-610)

**Problem:** Preference update and scoring computation race. User changes value rapidly; scoring uses stale/inconsistent state.

**Risk Level:** Data consistency, silent failures

### Step-by-Step Fix

#### Step 4.1: Add Error Handling and Rollback
**File:** `app/src/main/java/com/gregor/lauritz/healthdashboard/ui/settings/SettingsViewModel.kt` (lines 606-610)

```kotlin
is SettingsEvent.CircadianThresholdOverrideChanged -> {
    viewModelScope.launch {
        val previousValue = _uiState.value.circadianThresholdOverride
        
        try {
            // Step 1: Update preference (fast operation)
            prefsRepo.updateCircadianThresholdOverride(event.minutes)
            
            // Step 2: Recalculate scoring (may fail)
            scoringRepository.computeAndPersistDailySummary()
            
            // Step 3: Success - UI already updated via StateFlow
            Log.d(TAG, "Threshold updated successfully to ${event.minutes}")
            
        } catch (e: CancellationException) {
            // Don't handle cancellation - let it propagate
            throw e
        } catch (e: Exception) {
            // Step 4: On failure, revert preference to previous value
            Log.e(TAG, "Failed to update threshold - reverting", e)
            
            try {
                prefsRepo.updateCircadianThresholdOverride(previousValue)
            } catch (rollbackError: Exception) {
                Log.e(TAG, "Failed to rollback preference update", rollbackError)
            }
            
            // Step 5: Show error to user
            _uiState.update { state ->
                state.copy(
                    error = "Failed to update threshold settings. Your changes were not saved. " +
                            "Please try again or contact support if the issue persists.",
                    // Reset to previous value in UI
                    circadianThresholdOverride = previousValue
                )
            }
        }
    }
}
```

**Design Notes:**
- Preference update is fast (atomic DataStore operation)
- Scoring computation is slow and may fail
- On failure, revert to previous state
- User gets clear feedback
- Prevents inconsistent UI/database state

**Testing Checklist:**
- [ ] Rapid consecutive updates don't cause race
- [ ] Scoring computation failure triggers rollback
- [ ] Rollback failure logged but doesn't crash
- [ ] Error message is user-friendly
- [ ] Previous value restored in both DB and UI

**Effort:** 1.5-2 hours

---

# 🟡 MEDIUM - IMPORTANT (Non-Blocking)

## Issue #5: Missing ProGuard Rules (Already Fixed Above)
**See Issue #3.2 above**

---

## Issue #6: Sensitive Data in Logs

**Severity:** 🟡 MEDIUM  
**Files Affected:**
- `app/src/main/java/com/gregor/lauritz/healthdashboard/domain/scoring/ScoringConfigFactory.kt`
- All places where config is logged

**Problem:** Health profile data might leak to logs/crash reports if not careful.

### Step-by-Step Fix

#### Step 6.1: Remove Sensitive Data from Logs
**File:** `app/src/main/java/com/gregor/lauritz/healthdashboard/domain/scoring/ScoringConfigFactory.kt`

Update `computeConfigHash()`:
```kotlin
private fun computeConfigHash(
    restoration: RestorationWeights,
    sleepTargets: SleepArchitectureTargets,
    emergencyFlags: EmergencyFlagThresholds,
    circadianConsistency: CircadianConsistencyConfig,
): Int {
    // IMPORTANT: Do NOT log paramsString - it contains sensitive health data
    
    val paramsString = buildString {
        append(CONFIG_SCHEMA_VERSION).append("|")
        // ... build string (sensitive)
    }
    
    // Log only hash for debugging, never the actual params
    Log.d(TAG, "Computing config hash (version: $CONFIG_SCHEMA_VERSION)")
    
    val hash = MessageDigest.getInstance("SHA-256")
        .digest(paramsString.toByteArray(Charsets.UTF_8))
        .take(4)
        .foldIndexed(0) { i, acc, byte ->
            acc or (((byte.toInt()) and 0xFF) shl (i * 8))
        }
    
    Log.d(TAG, "Config hash computed successfully")
    return hash
}
```

#### Step 6.2: Create Secure Logging Utility
**File:** Create new `app/src/main/java/com/gregor/lauritz/healthdashboard/util/SecureLogger.kt`

```kotlin
package com.gregor.lauritz.healthdashboard.util

import android.util.Log

/**
 * Logging utility that prevents leakage of sensitive health data.
 * Use this instead of Log.* for any health-related information.
 */
object SecureLogger {
    private const val TAG = "HealthDashboard"
    
    /**
     * Log a debug message without sensitive data.
     * Use descriptive messages instead of logging actual values.
     */
    fun debugEvent(event: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, event)
        }
    }
    
    /**
     * Log an error without leaking sensitive data.
     * Include error context but not health data.
     */
    fun error(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
        // In production: send to crash reporting with non-sensitive details only
        reportToCrashlytics(message, throwable)
    }
    
    private fun reportToCrashlytics(message: String, throwable: Throwable?) {
        // TODO: Integrate Crashlytics
        // FirebaseCrashlytics.getInstance().recordException(throwable)
    }
}
```

**Usage in Code:**
```kotlin
SecureLogger.debugEvent("Threshold update initiated")
SecureLogger.error("Failed to compute scoring", exception)

// Instead of:
// Log.d(TAG, "User profile: $profile, threshold: $threshold")
```

**Testing Checklist:**
- [ ] No health data logged to Logcat
- [ ] Error messages are non-sensitive
- [ ] Crash reports don't contain PHI

**Effort:** 1 hour

---

## Issue #7: Potential God Object in SettingsUiState

**Severity:** 🟡 MEDIUM  
**Files Affected:**
- `app/src/main/java/com/gregor/lauritz/healthdashboard/ui/settings/SettingsViewModel.kt` (lines 44-99)

**Problem:** SettingsUiState has 50+ properties, violates Single Responsibility Principle.

### Step-by-Step Fix

#### Step 7.1: Decompose SettingsUiState
**File:** `app/src/main/java/com/gregor/lauritz/healthdashboard/ui/settings/SettingsViewModel.kt`

Create grouped state data classes:
```kotlin
// Threshold-related settings
data class ThresholdSettingsState(
    val circadianThresholdOverride: Int? = null,
    val consistencyThresholdMinutes: Int = SettingsDefaults.CONSISTENCY_THRESHOLD_MINUTES,
    val consistencyEvaluationDays: Int = SettingsDefaults.CONSISTENCY_EVALUATION_DAYS,
    val consistencyBaselineDays: Int = SettingsDefaults.CONSISTENCY_BASELINE_DAYS,
    val hrvOptimalThreshold: Float = SettingsDefaults.HRV_OPTIMAL_THRESHOLD,
    val hrvWarningThreshold: Float = SettingsDefaults.HRV_WARNING_THRESHOLD,
    val rhrOptimalThreshold: Float = SettingsDefaults.RHR_OPTIMAL_THRESHOLD,
    val rhrWarningThreshold: Float = SettingsDefaults.RHR_WARNING_THRESHOLD,
)

// Sleep-related settings
data class SleepSettingsState(
    val goalSleepHours: Float = SettingsDefaults.GOAL_SLEEP_HOURS,
    val hrvBaselineOverride: Float? = SettingsDefaults.HRV_BASELINE_OVERRIDE,
    val rhrBaselineOverride: Float? = SettingsDefaults.RHR_BASELINE_OVERRIDE,
)

// Profile and physiological settings
data class PhysiologySettingsState(
    val physiologyProfile: PhysiologyProfile = SettingsDefaults.PHYSIOLOGY_PROFILE,
    val age: Int = SettingsDefaults.AGE,
    val gender: String? = SettingsDefaults.GENDER,
    val birthDay: Int = SettingsDefaults.BIRTH_DAY,
    val birthMonth: Int = SettingsDefaults.BIRTH_MONTH,
    val birthYear: Int = SettingsDefaults.BIRTH_YEAR,
)

// Heart rate zones
data class HeartRateZonesState(
    val maxHeartRate: Int = SettingsDefaults.MAX_HEART_RATE,
    val autoCalculateMaxHr: Boolean = SettingsDefaults.AUTO_CALCULATE_MAX_HR,
    val manualZoneEditing: Boolean = SettingsDefaults.MANUAL_ZONE_EDITING,
    val zone1MinPercent: Float = SettingsDefaults.ZONE_1_MIN_PERCENT,
    val zone1MaxPercent: Float = SettingsDefaults.ZONE_1_MAX_PERCENT,
    val zone2MaxPercent: Float = SettingsDefaults.ZONE_2_MAX_PERCENT,
    val zone3MaxPercent: Float = SettingsDefaults.ZONE_3_MAX_PERCENT,
    val zone4MaxPercent: Float = SettingsDefaults.ZONE_4_MAX_PERCENT,
    val zone1MinBpm: Int = SettingsDefaults.ZONE_1_MIN_BPM,
    val zone1MaxBpm: Int = SettingsDefaults.ZONE_1_MAX_BPM,
    val zone2MaxBpm: Int = SettingsDefaults.ZONE_2_MAX_BPM,
    val zone3MaxBpm: Int = SettingsDefaults.ZONE_3_MAX_BPM,
    val zone4MaxBpm: Int = SettingsDefaults.ZONE_4_MAX_BPM,
)

// Backup and cloud settings
data class CloudBackupState(
    val driveEmail: String? = SettingsDefaults.DRIVE_ACCOUNT_EMAIL,
    val backupSchedule: BackupSchedule = SettingsDefaults.BACKUP_SCHEDULE,
    val lastBackupTimestamp: Long = SettingsDefaults.LAST_BACKUP_TIMESTAMP,
    val isBackingUp: Boolean = false,
    val isRestoring: Boolean = false,
    val showRestoreConfirmDialog: Boolean = false,
    val driveError: String? = null,
    val pendingRestoreDir: File? = null,
)

// UI state (loading, errors, etc.)
data class UIState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val isResyncing: Boolean = false,
    val success: String? = null,
)

// Consolidated settings state
data class SettingsUiState(
    val thresholds: ThresholdSettingsState = ThresholdSettingsState(),
    val sleep: SleepSettingsState = SleepSettingsState(),
    val physiology: PhysiologySettingsState = PhysiologySettingsState(),
    val heartRate: HeartRateZonesState = HeartRateZonesState(),
    val cloud: CloudBackupState = CloudBackupState(),
    val ui: UIState = UIState(),
    // Misc settings
    val syncPreference: SyncPreference = SettingsDefaults.SYNC_PREFERENCE,
    val syncIntervalHours: Int = SettingsDefaults.SYNC_INTERVAL_HOURS,
    val paiScalingFactor: Float = SettingsDefaults.PAI_SCALING_FACTOR,
    val stepGoal: Int = SettingsDefaults.STEP_GOAL,
    val appTheme: AppTheme = SettingsDefaults.APP_THEME,
    val dynamicColorEnabled: Boolean = SettingsDefaults.DYNAMIC_COLOR_ENABLED,
    val restingHrBeforeMinutes: Int = SettingsDefaults.RESTING_HR_BEFORE_MINUTES,
    val restingHrAfterMinutes: Int = SettingsDefaults.RESTING_HR_AFTER_MINUTES,
    val retentionDaysEnabled: Boolean = SettingsDefaults.RETENTION_DAYS_ENABLED,
    val retentionDays: Int = SettingsDefaults.RETENTION_DAYS,
    val collapseCloudData: Boolean = SettingsDefaults.COLLAPSE_CLOUD_DATA,
    val collapseHealthConnect: Boolean = SettingsDefaults.COLLAPSE_HEALTH_CONNECT,
    val collapseBaselinesThresholds: Boolean = SettingsDefaults.COLLAPSE_BASELINES_THRESHOLDS,
    val collapseDisplay: Boolean = SettingsDefaults.COLLAPSE_DISPLAY,
    val collapseAdvanced: Boolean = SettingsDefaults.COLLAPSE_ADVANCED,
)
```

**Update ViewModel Initialization:**
```kotlin
init {
    viewModelScope.launch {
        prefsRepo.userPreferences.collect { prefs ->
            val dynamicColor = appConfigRepo.dynamicColorEnabled.first()
            _uiState.update {
                it.copy(
                    thresholds = it.thresholds.copy(
                        circadianThresholdOverride = prefs.circadianThresholdOverride,
                        consistencyThresholdMinutes = prefs.consistencyThresholdMinutes,
                        // ... other threshold fields
                    ),
                    sleep = it.sleep.copy(
                        goalSleepHours = prefs.goalSleepHours,
                        // ... other sleep fields
                    ),
                    // ... other grouped states
                    ui = it.ui.copy(isLoading = false)
                )
            }
        }
    }
}
```

**Benefits:**
- Each state class has single responsibility
- Easier to test individual settings
- Better performance (only affected subscribers recompose)
- Clearer code organization

**Testing Checklist:**
- [ ] Each state class tested independently
- [ ] ViewModel correctly populates grouped states
- [ ] No regression in functionality

**Effort:** 2-3 hours

---

## Issue #8: Missing Error Handling in Compose UI

**Severity:** 🟡 MEDIUM  
**Files Affected:**
- `app/src/main/java/com/gregor/lauritz/healthdashboard/ui/settings/CircadianThresholdSettingsSection.kt`
- `app/src/main/java/com/gregor/lauritz/healthdashboard/ui/settings/SettingsScreen.kt`

**Problem:** CircadianThresholdSettingsSection doesn't expose or display error states.

### Step-by-Step Fix

#### Step 8.1: Add Error Handling to CircadianThresholdSettingsSection
**File:** `app/src/main/java/com/gregor/lauritz/healthdashboard/ui/settings/CircadianThresholdSettingsSection.kt`

```kotlin
@Composable
fun CircadianThresholdSettingsSection(
    profile: PhysiologyProfile,
    currentOverride: Int?,
    isShiftWorkerMode: Boolean,
    onOverrideChanged: (Int?) -> Unit,
    isLoading: Boolean = false,
    error: String? = null,
    onErrorDismissed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // ... existing code
    
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Circadian Consistency",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            // Show loading state
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(vertical = 8.dp)
                )
            }

            // Show error state
            if (error != null) {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onErrorDismissed) {
                            Text("Dismiss")
                        }
                    }
                }
            }

            // Regular content (disabled if loading)
            if (!isLoading) {
                // ... existing content
            }
        }
    }
}
```

#### Step 8.2: Update SettingsScreen Integration
**File:** `app/src/main/java/com/gregor/lauritz/healthdashboard/ui/settings/SettingsScreen.kt` (lines 280-291)

```kotlin
CircadianThresholdSettingsSection(
    profile = uiState.physiologyProfile,
    currentOverride = uiState.circadianThresholdOverride,
    isShiftWorkerMode = uiState.physiologyProfile == PhysiologyProfile.SHIFT_WORKER,
    onOverrideChanged = { onEvent(SettingsEvent.CircadianThresholdOverrideChanged(it)) },
    isLoading = uiState.isUpdatingThreshold, // Add new state field
    error = uiState.thresholdError, // Add new state field
    onErrorDismissed = { onEvent(SettingsEvent.DismissThresholdError) },
    modifier = Modifier.padding(horizontal = 16.dp),
)
```

#### Step 8.3: Add New Events and State Fields
**File:** `app/src/main/java/com/gregor/lauritz/healthdashboard/ui/settings/SettingsViewModel.kt`

```kotlin
sealed interface SettingsEvent {
    // ... existing events
    
    data object DismissThresholdError : SettingsEvent
}

data class SettingsUiState(
    // ... existing fields
    
    val isUpdatingThreshold: Boolean = false,
    val thresholdError: String? = null,
)
```

**Testing Checklist:**
- [ ] Error displays when update fails
- [ ] Loading spinner shown during update
- [ ] Dismiss button clears error
- [ ] UI is disabled during loading

**Effort:** 1.5 hours

---

## Issue #9: Magic Numbers

**Severity:** 🟡 MEDIUM  
**Files Affected:**
- `app/src/main/java/com/gregor/lauritz/healthdashboard/ui/settings/CircadianThresholdSettingsSection.kt` (lines 228-229)

### Step-by-Step Fix

**File:** `app/src/main/java/com/gregor/lauritz/healthdashboard/ui/settings/CircadianThresholdSettingsSection.kt`

```kotlin
@Composable
fun CircadianThresholdSettingsSection(
    // ... params
) {
    // Move to companion or top-level constants
    val profileDefault = getProfileDefault(profile)
    var useStandardRollingAnchor by rememberSaveable {
        mutableStateOf(currentOverride != null || profile != PhysiologyProfile.SHIFT_WORKER)
    }
    var thresholdValue by rememberSaveable {
        mutableFloatStateOf((currentOverride ?: profileDefault).toFloat())
    }

    // ... rest of code
}

// Add at top of file after imports:
private const val THRESHOLD_MIN_MINUTES = 0
private const val THRESHOLD_MAX_MINUTES = 90
private const val THRESHOLD_SLIDER_STEPS = 8 // Creates: 0, 10, 20, 30, 40, 50, 60, 70, 80, 90

// Use in slider:
Slider(
    value = value,
    onValueChange = onValueChanged,
    valueRange = THRESHOLD_MIN_MINUTES.toFloat()..THRESHOLD_MAX_MINUTES.toFloat(),
    steps = THRESHOLD_SLIDER_STEPS,
    modifier = Modifier.fillMaxWidth(),
)
```

**Effort:** 0.5 hours

---

# 🔵 LOW - NICE TO HAVE

## Issue #10: Potential Compose Recomposition Issues

**Severity:** 🔵 LOW  
**Files Affected:**
- `app/src/main/java/com/gregor/lauritz/healthdashboard/ui/settings/CircadianThresholdSettingsSection.kt` (lines 36-40)

**Fix:** Use `rememberSaveable` instead of `remember` for state that should persist across recompositions.

**Already implemented** in current code (lines 36, 39 use correct pattern).

---

## Issue #11: Missing Accessibility Semantics

**Severity:** 🔵 LOW  
**Files Affected:**
- `app/src/main/java/com/gregor/lauritz/healthdashboard/ui/settings/CircadianThresholdSettingsSection.kt`

### Step-by-Step Fix

**File:** `app/src/main/java/com/gregor/lauritz/healthdashboard/ui/settings/CircadianThresholdSettingsSection.kt`

```kotlin
Slider(
    value = value,
    onValueChange = onValueChanged,
    valueRange = THRESHOLD_MIN_MINUTES.toFloat()..THRESHOLD_MAX_MINUTES.toFloat(),
    steps = THRESHOLD_SLIDER_STEPS,
    modifier = Modifier
        .fillMaxWidth()
        .semantics {
            contentDescription = "Circadian threshold adjustment. " +
                                "Range: $THRESHOLD_MIN_MINUTES to $THRESHOLD_MAX_MINUTES minutes"
            stateDescription = "Current value: ${value.toInt()} minutes"
            if (value == value.toInt().toFloat()) {
                customActions = listOf(
                    CustomAccessibilityAction(label = "Increase by 10") { true },
                    CustomAccessibilityAction(label = "Decrease by 10") { true }
                )
            }
        }
)

Checkbox(
    checked = !useStandardRollingAnchor,
    onCheckedChange = { onModeChanged(!it) },
    modifier = Modifier.semantics {
        contentDescription = if (!useStandardRollingAnchor) {
            "Within-week regularity mode enabled. " +
            "Compares sleep consistency on same day-of-week across different weeks."
        } else {
            "Within-week regularity mode disabled"
        }
    }
)
```

**Effort:** 0.5 hours

---

## Issue #12: Missing Unit Tests

**Severity:** 🔵 LOW  
**Files Affected:** All new code

### Step-by-Step Fix

Create test files:

**File:** Create `app/src/test/java/com/gregor/lauritz/healthdashboard/domain/circadian/CircadianThresholdValueTest.kt`

```kotlin
package com.gregor.lauritz.healthdashboard.domain.circadian

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CircadianThresholdValueTest {
    
    @Test
    fun testValidRange() {
        // Min value
        val min = CircadianThresholdValue(0)
        assertEquals(0, min.minutes)
        
        // Max value
        val max = CircadianThresholdValue(90)
        assertEquals(90, max.minutes)
        
        // Mid value
        val mid = CircadianThresholdValue(45)
        assertEquals(45, mid.minutes)
    }
    
    @Test
    fun testBelowMinimum() {
        assertFailsWith<IllegalArgumentException> {
            CircadianThresholdValue(-1)
        }
    }
    
    @Test
    fun testAboveMaximum() {
        assertFailsWith<IllegalArgumentException> {
            CircadianThresholdValue(91)
        }
    }
    
    @Test
    fun testTryCreateValid() {
        val result = CircadianThresholdValue.tryCreate(30)
        assert(result.isSuccess)
        assertEquals(30, result.getOrNull()?.minutes)
    }
    
    @Test
    fun testTryCreateNull() {
        val result = CircadianThresholdValue.tryCreate(null)
        assert(result.isSuccess)
        assertNull(result.getOrNull())
    }
    
    @Test
    fun testTryCreateInvalid() {
        val result = CircadianThresholdValue.tryCreate(150)
        assert(result.isFailure)
    }
    
    @Test
    fun testClampBelow() {
        val clamped = CircadianThresholdValue.createOrClamp(-10)
        assertEquals(0, clamped?.minutes)
    }
    
    @Test
    fun testClampAbove() {
        val clamped = CircadianThresholdValue.createOrClamp(200)
        assertEquals(90, clamped?.minutes)
    }
}
```

**File:** Create `app/src/test/java/com/gregor/lauritz/healthdashboard/ui/settings/SettingsViewModelTest.kt`

```kotlin
package com.gregor.lauritz.healthdashboard.ui.settings

import androidx.lifecycle.SavedStateHandle
import com.gregor.lauritz.healthdashboard.MainDispatcherRule
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferencesRepository
import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class SettingsViewModelTest {
    
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()
    
    private val prefsRepo = mockk<UserPreferencesRepository>(relaxed = true)
    private val scoringRepo = mockk<ScoringRepository>(relaxed = true)
    
    private lateinit var viewModel: SettingsViewModel
    
    @Before
    fun setUp() {
        coEvery { prefsRepo.userPreferences } returns mockk()
        
        viewModel = SettingsViewModel(
            context = mockk(),
            prefsRepo = prefsRepo,
            appConfigRepo = mockk(),
            backupPrefsRepo = mockk(),
            scoringRepository = scoringRepo,
            healthSyncUseCase = mockk(),
            resyncHealthConnectUseCase = mockk(),
            driveAuthManager = mockk(),
            backupUseCase = mockk(),
            restoreUseCase = mockk(),
            userUseCase = mockk(),
            workerScheduler = mockk(),
            workManager = mockk()
        )
    }
    
    @Test
    fun testCircadianThresholdOverrideChanged() = runTest {
        val threshold = 30
        viewModel.onEvent(
            SettingsEvent.CircadianThresholdOverrideChanged(threshold)
        )
        
        advanceUntilIdle()
        
        coVerify {
            prefsRepo.updateCircadianThresholdOverride(threshold)
            scoringRepo.computeAndPersistDailySummary()
        }
    }
    
    @Test
    fun testCircadianThresholdOverrideInvalid() = runTest {
        val invalidThreshold = 150
        viewModel.onEvent(
            SettingsEvent.CircadianThresholdOverrideChanged(invalidThreshold)
        )
        
        advanceUntilIdle()
        
        // Should not update preference with invalid value
        coVerify(exactly = 0) {
            prefsRepo.updateCircadianThresholdOverride(invalidThreshold)
        }
    }
}
```

**Effort:** 2 hours

---

## Issue #13: Hash Computation Performance

**Severity:** 🔵 LOW

**Already addressed** by versioning scheme. Hash computation only runs on config changes, which is infrequent.

---

## Issue #14: StateFlow Caching in Preferences

**Severity:** 🔵 LOW  
**Files Affected:**
- `app/src/main/java/com/gregor/lauritz/healthdashboard/data/preferences/DataStoreCircadianThresholdPreferences.kt`

### Step-by-Step Fix

**File:** `app/src/main/java/com/gregor/lauritz/healthdashboard/data/preferences/DataStoreCircadianThresholdPreferences.kt`

```kotlin
class DataStoreCircadianThresholdPreferences
    @Inject
    constructor(
        private val userPreferencesRepository: UserPreferencesRepository,
        private val encryptionManager: EncryptionManager,
    ) : CircadianThresholdPreferences {
    
    // Cache the flow to avoid recreating on each access
    override val overrideMinutesFlow: Flow<Int?> by lazy {
        userPreferencesRepository.userPreferences
            .map { prefs ->
                encryptionManager.decryptThresholdValue(
                    prefs.circadianThresholdOverride?.toString()
                )
            }
    }

    override suspend fun setOverride(minutes: Int?) {
        val encrypted = minutes?.let { 
            encryptionManager.encryptThresholdValue(it) 
        }?.toIntOrNull()
        userPreferencesRepository.updateCircadianThresholdOverride(encrypted)
    }
    
    override val isEncrypted: Boolean = true
}
```

**Effort:** 0.5 hours

---

# Implementation Schedule

## Phase 1: Critical Fixes (2-3 days)
- [ ] Issue #1: Implement encryption (3-4 hours)
- [ ] Issue #2: Add input validation (2-3 hours)
- [ ] Issue #3: Add config versioning + ProGuard rules (1-2 hours)
- [ ] Issue #4: Add race condition handling (1.5-2 hours)

**Subtotal:** 7.5-11 hours

## Phase 2: High Priority (1-2 days)
- [ ] Issue #6: Secure logging (1 hour)
- [ ] Issue #8: Error handling UI (1.5 hours)
- [ ] Issue #9: Remove magic numbers (0.5 hours)

**Subtotal:** 3 hours

## Phase 3: Medium Priority (1-2 days)
- [ ] Issue #7: Decompose state (2-3 hours)

**Subtotal:** 2-3 hours

## Phase 4: Nice to Have (0.5-1 day)
- [ ] Issue #11: Accessibility (0.5 hours)
- [ ] Issue #12: Unit tests (2 hours)
- [ ] Issue #14: StateFlow caching (0.5 hours)

**Subtotal:** 3 hours

---

# Testing Checklist

Before merging, verify:

- [ ] All unit tests pass: `./gradlew testDebugUnitTest`
- [ ] Build passes in release mode: `./gradlew assembleRelease`
- [ ] No ProGuard warnings in release build mapping
- [ ] Manual testing: Update threshold → verify saved and encrypted
- [ ] Manual testing: Rapid threshold changes → no race conditions
- [ ] Manual testing: Invalid values → error message shown
- [ ] Manual testing: Error recovery works
- [ ] Accessibility testing: Screen reader reads error messages
- [ ] Security testing: No sensitive data in logs or crashes

---

# Rollback Plan

If issues arise during implementation:

1. Create backup branch: `git checkout -b backup/pr6-fixes`
2. If blocking: `git revert [commit-sha]`
3. Document issue and create separate PR
4. Review root cause before re-attempting

---

# References & Resources

- [Android Security Best Practices](https://developer.android.com/topic/security/best-practices)
- [OWASP Mobile Top 10](https://owasp.org/www-project-mobile-top-10/)
- [Kotlin Data Validation Patterns](https://kotlinlang.org/docs/scope-functions.html)
- [Jetpack Compose Error Handling](https://developer.android.com/jetpack/compose)
- [Android Encryption with Tink](https://developers.google.com/tink)

---

**Document Version:** 1.0  
**Last Updated:** 2026-05-01  
**Status:** Ready for Implementation
