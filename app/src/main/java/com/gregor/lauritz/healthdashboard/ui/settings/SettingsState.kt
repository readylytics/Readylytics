package com.gregor.lauritz.healthdashboard.ui.settings

import com.gregor.lauritz.healthdashboard.data.preferences.AppTheme
import com.gregor.lauritz.healthdashboard.data.preferences.BackupSchedule
import com.gregor.lauritz.healthdashboard.data.preferences.PhysiologyProfile
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsDefaults
import com.gregor.lauritz.healthdashboard.data.preferences.SyncPreference
import com.gregor.lauritz.healthdashboard.domain.scoring.TrimpModel
import java.io.File

data class ThresholdSettingsState(
    val circadianThresholdOverride: Int? = null,
    val isUpdatingThreshold: Boolean = false,
    val thresholdError: String? = null,
    val hrvOptimalThreshold: Float = SettingsDefaults.HRV_OPTIMAL_THRESHOLD,
    val hrvWarningThreshold: Float = SettingsDefaults.HRV_WARNING_THRESHOLD,
    val rhrOptimalThreshold: Float = SettingsDefaults.RHR_OPTIMAL_THRESHOLD,
    val rhrWarningThreshold: Float = SettingsDefaults.RHR_WARNING_THRESHOLD,
    val consistencyThresholdMinutes: Int = SettingsDefaults.CONSISTENCY_THRESHOLD_MINUTES,
    val consistencyEvaluationDays: Int = SettingsDefaults.CONSISTENCY_EVALUATION_DAYS,
    val consistencyBaselineDays: Int = SettingsDefaults.CONSISTENCY_BASELINE_DAYS,
)

data class SleepSettingsState(
    val goalSleepHours: Float = SettingsDefaults.GOAL_SLEEP_HOURS,
    val hrvBaselineOverride: Float? = SettingsDefaults.HRV_BASELINE_OVERRIDE,
    val rhrBaselineOverride: Float? = SettingsDefaults.RHR_BASELINE_OVERRIDE,
    val restingHrBeforeMinutes: Int = SettingsDefaults.RESTING_HR_BEFORE_MINUTES,
    val restingHrAfterMinutes: Int = SettingsDefaults.RESTING_HR_AFTER_MINUTES,
)

data class PhysiologySettingsState(
    val physiologyProfile: PhysiologyProfile = SettingsDefaults.PHYSIOLOGY_PROFILE,
    val age: Int = SettingsDefaults.AGE,
    val birthDay: Int = SettingsDefaults.BIRTH_DAY,
    val birthMonth: Int = SettingsDefaults.BIRTH_MONTH,
    val birthYear: Int = SettingsDefaults.BIRTH_YEAR,
    val gender: String? = SettingsDefaults.GENDER,
)

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

data class CloudBackupState(
    val driveAccountEmail: String? = SettingsDefaults.DRIVE_ACCOUNT_EMAIL,
    val backupSchedule: BackupSchedule = SettingsDefaults.BACKUP_SCHEDULE,
    val lastBackupTimestamp: Long = SettingsDefaults.LAST_BACKUP_TIMESTAMP,
    val isBackingUp: Boolean = false,
    val isRestoring: Boolean = false,
    val showRestoreConfirmDialog: Boolean = false,
    val driveError: String? = null,
    val restoreSuccess: Boolean = false,
    val pendingRestoreDir: File? = null,
)

data class UIState(
    val isLoading: Boolean = true,
    val isResyncing: Boolean = false,
    val appTheme: AppTheme = SettingsDefaults.APP_THEME,
    val dynamicColorEnabled: Boolean = SettingsDefaults.DYNAMIC_COLOR_ENABLED,
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
    val trimpModel: TrimpModel = SettingsDefaults.TRIMP_MODEL,
    val banisterMultiplier: Float = PhysiologyProfile.GENERAL.banisterMultiplier,
    val chengBeta: Float = PhysiologyProfile.GENERAL.defaultChengBeta,
    val itrimB: Float = PhysiologyProfile.GENERAL.defaultItrimB,
)

data class SyncSettingsState(
    val syncPreference: SyncPreference = SettingsDefaults.SYNC_PREFERENCE,
    val syncIntervalHours: Int = SettingsDefaults.SYNC_INTERVAL_HOURS,
    val isResyncing: Boolean = false,
)
