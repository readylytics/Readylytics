package com.gregor.lauritz.healthdashboard.data.preferences

import com.gregor.lauritz.healthdashboard.domain.scoring.TrimpModel
import java.time.LocalDate
import java.time.YearMonth

enum class BackupSchedule { MANUAL, DAILY, WEEKLY }

data class UserPreferences(
    val goalSleepHours: Float = SettingsDefaults.GOAL_SLEEP_HOURS,
    val hrvBaselineOverride: Float? = SettingsDefaults.HRV_BASELINE_OVERRIDE,
    val rhrBaselineOverride: Float? = SettingsDefaults.RHR_BASELINE_OVERRIDE,
    val syncPreference: SyncPreference = SettingsDefaults.SYNC_PREFERENCE,
    val syncIntervalHours: Int = SettingsDefaults.SYNC_INTERVAL_HOURS,
    val lastSyncTimestamp: Long = SettingsDefaults.LAST_SYNC_TIMESTAMP,
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
    val age: Int = SettingsDefaults.AGE,
    val birthDate: String? = null,
    val gender: Gender? = null,
    val heightCm: Float? = SettingsDefaults.HEIGHT_CM,
    val hrvOptimalThreshold: Float = SettingsDefaults.HRV_OPTIMAL_THRESHOLD,
    val hrvWarningThreshold: Float = SettingsDefaults.HRV_WARNING_THRESHOLD,
    val rhrOptimalThreshold: Float = SettingsDefaults.RHR_OPTIMAL_THRESHOLD,
    val rhrWarningThreshold: Float = SettingsDefaults.RHR_WARNING_THRESHOLD,
    val restingHrPercentile: Int = SettingsDefaults.RESTING_HR_PERCENTILE,
    val appTheme: AppTheme = SettingsDefaults.APP_THEME,
    val driveAccountEmail: String? = SettingsDefaults.DRIVE_ACCOUNT_EMAIL,
    val backupSchedule: BackupSchedule = SettingsDefaults.BACKUP_SCHEDULE,
    val lastBackupTimestamp: Long = SettingsDefaults.LAST_BACKUP_TIMESTAMP,
    val consistencyThresholdMinutes: Int = SettingsDefaults.CONSISTENCY_THRESHOLD_MINUTES,
    val consistencyEvaluationDays: Int = SettingsDefaults.CONSISTENCY_EVALUATION_DAYS,
    val consistencyBaselineDays: Int = SettingsDefaults.CONSISTENCY_BASELINE_DAYS,
    val paiScalingFactor: Float = SettingsDefaults.PAI_SCALING_FACTOR,
    val stepGoal: Int = SettingsDefaults.STEP_GOAL,
    val retentionDaysEnabled: Boolean = SettingsDefaults.RETENTION_DAYS_ENABLED,
    val retentionDays: Int = SettingsDefaults.RETENTION_DAYS,
    val collapseCloudData: Boolean = SettingsDefaults.COLLAPSE_CLOUD_DATA,
    val collapseHealthConnect: Boolean = SettingsDefaults.COLLAPSE_HEALTH_CONNECT,
    val collapseBaselinesThresholds: Boolean = SettingsDefaults.COLLAPSE_BASELINES_THRESHOLDS,
    val collapseDisplay: Boolean = SettingsDefaults.COLLAPSE_DISPLAY,
    val collapseAdvanced: Boolean = SettingsDefaults.COLLAPSE_ADVANCED,
    val aboutDismissed: Boolean = SettingsDefaults.ABOUT_DISMISSED,
    val physiologyProfile: PhysiologyProfile = SettingsDefaults.PHYSIOLOGY_PROFILE,
    val installDate: Long = SettingsDefaults.INSTALL_DATE,
    /** Encrypted ciphertext via EncryptionManager. DO NOT use as plaintext. Decrypt before reading. */
    val circadianThresholdOverride: String? = SettingsDefaults.CIRCADIAN_THRESHOLD_OVERRIDE,
    val dynamicColorEnabled: Boolean = SettingsDefaults.DYNAMIC_COLOR_ENABLED,
    val fallbackThemeColor: FallbackThemeColor = SettingsDefaults.FALLBACK_THEME_COLOR,
    val trimpModel: TrimpModel = SettingsDefaults.TRIMP_MODEL,
    val banisterMultiplier: Float = PhysiologyProfile.GENERAL.banisterMultiplier,
    val chengBeta: Float = PhysiologyProfile.GENERAL.defaultChengBeta,
    val itrimB: Float = PhysiologyProfile.GENERAL.defaultItrimB,
    val primaryDeviceName: String? = null,
    /**
     * Per–data-type source device selection. Key = [com.gregor.lauritz.healthdashboard.domain.model.HealthDataType]
     * name, value = device label. A missing key means "All devices" for that data type.
     */
    val deviceByDataType: Map<String, String> = emptyMap(),
    val backupDirectoryUri: String? = null,
    /**
     * Stored Base64-encoded AES-256-GCM ciphertext of the backup password (encrypted via Google Tink).
     * NOTE: Named "backupPasswordHash" for database/Proto schema backwards compatibility,
     * but stores decryptable ciphertext required by background backup workers.
     */
    val backupPasswordHash: String? = null,
    val isBirthdayConfigured: Boolean = SettingsDefaults.IS_BIRTHDAY_CONFIGURED,
    val unitSystem: UnitSystem = SettingsDefaults.UNIT_SYSTEM,
    val backgroundSyncEnabled: Boolean = SettingsDefaults.BACKGROUND_SYNC_ENABLED,
    val backgroundSyncIntervalMinutes: Int = SettingsDefaults.BACKGROUND_SYNC_INTERVAL.minutes,
)

fun UserPreferencesProto.toDomainModel(): UserPreferences {
    val profile = PhysiologyProfile.valueOf(physiologyProfile.name.removePrefix("PROFILE_"))
    return UserPreferences(
        goalSleepHours = goalSleepHours,
        hrvBaselineOverride = if (hasHrvBaselineOverride()) hrvBaselineOverride else null,
        rhrBaselineOverride = if (hasRhrBaselineOverride()) rhrBaselineOverride else null,
        syncPreference = SyncPreference.valueOf(syncPreference.name.removePrefix("SYNC_")),
        syncIntervalHours = syncIntervalHours,
        lastSyncTimestamp = lastSyncTimestamp,
        maxHeartRate = maxHeartRate,
        autoCalculateMaxHr = autoCalculateMaxHr,
        manualZoneEditing = manualZoneEditing,
        zone1MinPercent = zone1MinPercent,
        zone1MaxPercent = zone1MaxPercent,
        zone2MaxPercent = zone2MaxPercent,
        zone3MaxPercent = zone3MaxPercent,
        zone4MaxPercent = zone4MaxPercent,
        zone1MinBpm = zone1MinBpm,
        zone1MaxBpm = zone1MaxBpm,
        zone2MaxBpm = zone2MaxBpm,
        zone3MaxBpm = zone3MaxBpm,
        zone4MaxBpm = zone4MaxBpm,
        age = age,
        birthDate = migrateBirthdateFields(birthDay, birthMonth, birthYear),
        gender = if (hasGender()) Gender.fromString(gender) else null,
        heightCm = if (hasHeightCm()) heightCm else null,
        hrvOptimalThreshold = hrvOptimalThreshold,
        hrvWarningThreshold = hrvWarningThreshold,
        rhrOptimalThreshold = rhrOptimalThreshold,
        rhrWarningThreshold = rhrWarningThreshold,
        restingHrPercentile =
            if (restingHrPercentile ==
                0
            ) {
                SettingsDefaults.RESTING_HR_PERCENTILE
            } else {
                restingHrPercentile.coerceIn(1, 15)
            },
        appTheme = AppTheme.valueOf(appTheme.name.removePrefix("THEME_")),
        driveAccountEmail = if (hasDriveAccountEmail()) driveAccountEmail else null,
        backupSchedule = BackupSchedule.valueOf(backupSchedule.name.removePrefix("BACKUP_")),
        lastBackupTimestamp = lastBackupTimestamp,
        consistencyThresholdMinutes = consistencyThresholdMinutes,
        consistencyEvaluationDays = consistencyEvaluationDays,
        consistencyBaselineDays = consistencyBaselineDays,
        paiScalingFactor = paiScalingFactor,
        stepGoal = stepGoal,
        retentionDaysEnabled = retentionDaysEnabled,
        retentionDays = retentionDays,
        collapseCloudData = collapseCloudData,
        collapseHealthConnect = collapseHealthConnect,
        collapseBaselinesThresholds = collapseBaselinesThresholds,
        collapseDisplay = collapseDisplay,
        collapseAdvanced = collapseAdvanced,
        aboutDismissed = aboutDismissed,
        physiologyProfile = profile,
        installDate = installDate,
        circadianThresholdOverride = if (hasCircadianThresholdOverride()) circadianThresholdOverride else null,
        dynamicColorEnabled = dynamicColorEnabled,
        fallbackThemeColor =
            when (fallbackThemeColor) {
                FallbackThemeColorProto.FALLBACK_BRAND_PURPLE -> FallbackThemeColor.BRAND_PURPLE
                FallbackThemeColorProto.FALLBACK_BRAND_BLUE -> FallbackThemeColor.BRAND_BLUE
                FallbackThemeColorProto.FALLBACK_TURQUOISE -> FallbackThemeColor.TURQUOISE
                FallbackThemeColorProto.FALLBACK_GREEN -> FallbackThemeColor.GREEN
                FallbackThemeColorProto.FALLBACK_RECOVERY_BLUE -> FallbackThemeColor.RECOVERY_BLUE
                else -> SettingsDefaults.FALLBACK_THEME_COLOR
            },
        trimpModel =
            when (trimpMethod) {
                TrimpMethodProto.TRIMP_ITRIMP -> TrimpModel.I_TRIMP
                TrimpMethodProto.TRIMP_CHENG -> TrimpModel.CHENG
                else -> TrimpModel.BANISTER
            },
        banisterMultiplier = if (paiCalibration > 0f) paiCalibration else profile.banisterMultiplier,
        chengBeta = if (this.chengBeta > 0f) this.chengBeta else profile.defaultChengBeta,
        itrimB = if (itrimpB > 0f) itrimpB else profile.defaultItrimB,
        primaryDeviceName = if (hasPrimaryDeviceName()) primaryDeviceName else null,
        deviceByDataType = deviceByDataTypeMap.toMap(),
        backupDirectoryUri = if (hasBackupDirectoryUri()) backupDirectoryUri else null,
        backupPasswordHash = if (hasBackupPasswordHash()) backupPasswordHash else null,
        isBirthdayConfigured = isBirthdayConfigured,
        unitSystem =
            when (unitSystem) {
                UnitSystemProto.UNIT_METRIC -> UnitSystem.METRIC
                UnitSystemProto.UNIT_IMPERIAL -> UnitSystem.IMPERIAL
                else -> SettingsDefaults.UNIT_SYSTEM
            },
        backgroundSyncEnabled = backgroundSyncEnabled,
        backgroundSyncIntervalMinutes =
            if (backgroundSyncIntervalMinutes ==
                0
            ) {
                SettingsDefaults.BACKGROUND_SYNC_INTERVAL.minutes
            } else {
                backgroundSyncIntervalMinutes
            },
    )
}

private fun migrateBirthdateFields(
    day: Int,
    month: Int,
    year: Int,
): String? {
    if (day == 0 || month == 0 || year == 0) return null

    return try {
        val clampedMonth = month.coerceIn(1, 12)
        val daysInMonth = YearMonth.of(year, clampedMonth).lengthOfMonth()
        val clampedDay = day.coerceIn(1, daysInMonth)
        val birthDate = LocalDate.of(year, clampedMonth, clampedDay)

        if (birthDate > LocalDate.now()) {
            null
        } else {
            birthDate.toString()
        }
    } catch (e: Exception) {
        null
    }
}
