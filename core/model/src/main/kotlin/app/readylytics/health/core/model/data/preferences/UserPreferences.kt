package app.readylytics.health.core.model.data.preferences

import app.readylytics.health.core.model.domain.preferences.Vo2MaxEstimationMethod
import app.readylytics.health.core.model.domain.preferences.Vo2MaxSourceMode

import app.readylytics.health.core.model.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.core.model.domain.scoring.LoadSourceMode
import app.readylytics.health.core.model.domain.scoring.SleepScoreWeightProfile
import app.readylytics.health.core.model.domain.scoring.TrainingReadinessConfig
import app.readylytics.health.core.model.domain.scoring.TrimpModel
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

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
    val backupSchedule: BackupSchedule = SettingsDefaults.BACKUP_SCHEDULE,
    val lastBackupTimestamp: Long = SettingsDefaults.LAST_BACKUP_TIMESTAMP,
    val consistencyThresholdMinutes: Int = SettingsDefaults.CONSISTENCY_THRESHOLD_MINUTES,
    val consistencyEvaluationDays: Int = SettingsDefaults.CONSISTENCY_EVALUATION_DAYS,
    val consistencyBaselineDays: Int = SettingsDefaults.CONSISTENCY_BASELINE_DAYS,
    val hrrToleranceSeconds: Int = SettingsDefaults.HRR_TOLERANCE_SECONDS,
    val rasScalingFactor: Float = SettingsDefaults.RAS_SCALING_FACTOR,
    val stepGoal: Int = SettingsDefaults.STEP_GOAL,
    val retentionDaysEnabled: Boolean = SettingsDefaults.RETENTION_DAYS_ENABLED,
    val retentionDays: Int = SettingsDefaults.RETENTION_DAYS,
    val aboutDismissed: Boolean = SettingsDefaults.ABOUT_DISMISSED,
    val physiologyProfile: PhysiologyProfile = SettingsDefaults.PHYSIOLOGY_PROFILE,
    val installDate: Long = SettingsDefaults.INSTALL_DATE,
    /** Encrypted ciphertext via EncryptionManager. DO NOT use as plaintext. Decrypt before reading. */
    val circadianThresholdOverride: String? = SettingsDefaults.CIRCADIAN_THRESHOLD_OVERRIDE,
    val dynamicColorEnabled: Boolean = SettingsDefaults.DYNAMIC_COLOR_ENABLED,
    val fallbackThemeColor: FallbackThemeColor = SettingsDefaults.FALLBACK_THEME_COLOR,
    val trimpModel: TrimpModel = SettingsDefaults.TRIMP_MODEL,
    val banisterMultiplier: Float = PhysiologyProfile.ACTIVE.banisterMultiplier,
    val chengBeta: Float = PhysiologyProfile.ACTIVE.defaultChengBeta,
    val itrimB: Float = PhysiologyProfile.ACTIVE.defaultItrimB,
    val primaryDeviceName: String? = null,
    /**
     * Per–data-type source device selection. Key = [app.readylytics.health.core.model.domain.model.HealthDataType]
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
    val weekStartDay: DayOfWeek = SettingsDefaults.WEEK_START_DAY,
    val backgroundSyncEnabled: Boolean = SettingsDefaults.BACKGROUND_SYNC_ENABLED,
    val backgroundSyncIntervalMinutes: Int = SettingsDefaults.BACKGROUND_SYNC_INTERVAL.minutes,
    val isCustomPaletteEnabled: Boolean = SettingsDefaults.IS_CUSTOM_PALETTE_ENABLED,
    val customSecondaryColor: Long = SettingsDefaults.CUSTOM_SECONDARY_COLOR,
    val customTertiaryColor: Long = SettingsDefaults.CUSTOM_TERTIARY_COLOR,
    val customPrimaryColor: Long = SettingsDefaults.CUSTOM_PRIMARY_COLOR,
    /**
     * IANA timezone id used for all scoring day-boundary math. Stored so identical SQLite +
     * identical preferences reproduce identical scores regardless of the device timezone.
     * Empty = un-seeded; resolve via [scoringZone] which falls back to the device zone.
     */
    val scoringZoneId: String = SettingsDefaults.SCORING_ZONE_ID,
    val deviceChangeNoticeDismissed: Boolean = SettingsDefaults.DEVICE_CHANGE_NOTICE_DISMISSED,
    val bulkDisplayModeNoticeDismissed: Boolean = SettingsDefaults.BULK_DISPLAY_MODE_NOTICE_DISMISSED,
    val lastGlobalDisplayMode: DashboardCardDisplayMode? = SettingsDefaults.LAST_GLOBAL_DISPLAY_MODE,
    val strainLoadSourceMode: LoadSourceMode = SettingsDefaults.STRAIN_LOAD_SOURCE_MODE,
    val rasSourceMode: LoadSourceMode = SettingsDefaults.RAS_SOURCE_MODE,
    val coreMergeGapMinutes: Int = SettingsDefaults.CORE_MERGE_GAP_MINUTES,
    val supplementalCutoffMinutesOfDay: Int = SettingsDefaults.SUPPLEMENTAL_CUTOFF_MINUTES_OF_DAY,
    val minimumCountedSleepSegmentMinutes: Int = SettingsDefaults.MINIMUM_COUNTED_SLEEP_SEGMENT_MINUTES,
    val supplementalArchitectureCoveragePercent: Int =
        SettingsDefaults.SUPPLEMENTAL_ARCHITECTURE_COVERAGE_PERCENT,
    val bodyTempElevatedThresholdCelsius: Float = SettingsDefaults.BODY_TEMP_ELEVATED_THRESHOLD_CELSIUS,
    val sleepScoreWeightProfile: SleepScoreWeightProfile = SleepScoreWeightProfile.DEFAULT,
    val hypersomniaOnsetPercent: Int = SettingsDefaults.HYPERSOMNIA_ONSET_PERCENT,
    val scoringVersion: Int = 0,
    val lastRecalcSleepScoreWeightProfile: SleepScoreWeightProfile? = null,
    val lastRecalcGoalSleepHours: Float? = null,
    val lastRecalcHypersomniaOnsetPercent: Int? = null,
    val trimpNormalizationMigrated: Boolean = false,
    val residualFatigueHalfLifeHours: Float = SettingsDefaults.RESIDUAL_FATIGUE_HALF_LIFE_HOURS,
    val residualFatigueGain: Float = SettingsDefaults.RESIDUAL_FATIGUE_GAIN,
    val trainingReadinessResidualFatigueScale: Float =
        SettingsDefaults.TRAINING_READINESS_RESIDUAL_FATIGUE_SCALE,
    val trainingReadinessLoadBalanceWeight: Float =
        SettingsDefaults.TRAINING_READINESS_LOAD_BALANCE_WEIGHT,
    val lastAppliedTrainingReadinessResidualFatigueScale: Float? = null,
    val lastAppliedTrainingReadinessLoadBalanceWeight: Float? = null,
    val vo2MaxSourceMode: Vo2MaxSourceMode = Vo2MaxSourceMode.AUTO,
    val vo2MaxEstimationMethod: Vo2MaxEstimationMethod = Vo2MaxEstimationMethod.HR_RATIO,
)

/**
 * Resolves the timezone used for all scoring day-boundary computations. A blank or invalid
 * [UserPreferences.scoringZoneId] falls back to the device zone, so behavior is unchanged
 * until the seed migration captures a concrete zone. Once seeded, scoring is timezone-
 * deterministic: the same data + preferences reproduce identical scores regardless of the
 * device timezone.
 */
fun UserPreferences.scoringZone(): ZoneId =
    scoringZoneId
        .takeIf { it.isNotBlank() }
        ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        ?: ZoneId.systemDefault()

/** Returns the last successfully applied configuration, or the default until one exists. */
fun UserPreferences.appliedTrainingReadinessConfig(): TrainingReadinessConfig {
    val scale = lastAppliedTrainingReadinessResidualFatigueScale
    val weight = lastAppliedTrainingReadinessLoadBalanceWeight
    return if (scale == null || weight == null) {
        TrainingReadinessConfig.fromStored(
            SettingsDefaults.TRAINING_READINESS_RESIDUAL_FATIGUE_SCALE,
            SettingsDefaults.TRAINING_READINESS_LOAD_BALANCE_WEIGHT,
        )
    } else {
        TrainingReadinessConfig.fromStored(scale, weight)
    }
}
