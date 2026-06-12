package app.readylytics.health.ui.settings

import app.readylytics.health.data.preferences.AppTheme
import app.readylytics.health.data.preferences.BackupSchedule
import app.readylytics.health.data.preferences.FallbackThemeColor
import app.readylytics.health.data.preferences.Gender
import app.readylytics.health.data.preferences.PhysiologyProfile
import app.readylytics.health.data.preferences.SettingsDefaults
import app.readylytics.health.data.preferences.SyncPreference
import app.readylytics.health.data.preferences.UnitSystem
import app.readylytics.health.domain.backup.BackupFileInfo
import app.readylytics.health.domain.scoring.TrimpModel
import app.readylytics.health.ui.common.UiText
import java.time.LocalDate

data class ThresholdSettingsState(
    val circadianThresholdOverride: Int? = null,
    val isUpdatingThreshold: Boolean = false,
    val thresholdError: UiText? = null,
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
    val restingHrPercentile: Int = SettingsDefaults.RESTING_HR_PERCENTILE,
)

data class PhysiologySettingsState(
    val physiologyProfile: PhysiologyProfile = SettingsDefaults.PHYSIOLOGY_PROFILE,
    val age: Int = SettingsDefaults.AGE,
    val birthDate: LocalDate? = null,
    val gender: Gender? = null,
    val heightCm: Float? = SettingsDefaults.HEIGHT_CM,
    val unitSystem: UnitSystem = UnitSystem.METRIC,
    val showBirthdatePickerDialog: Boolean = false,
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

data class LocalBackupState(
    val lastBackupTimestamp: Long = SettingsDefaults.LAST_BACKUP_TIMESTAMP,
    val backupSchedule: BackupSchedule = SettingsDefaults.BACKUP_SCHEDULE,
    val backupDirectory: String? = null,
    val isBackingUp: Boolean = false,
    val isRestoring: Boolean = false,
    val isReencrypting: Boolean = false,
    val isPasswordSet: Boolean = false,
    val showSetPasswordDialog: Boolean = false,
    val showRestoreConfirmDialog: Boolean = false,
    val backupError: UiText? = null,
    val restoreSuccess: Boolean = false,
    val pendingRestoreFile: BackupFileInfo? = null,
    val availableBackups: List<BackupFileInfo> = emptyList(),
    val passwordVerificationResult: Boolean? = null,
)

data class UIState(
    val isLoading: Boolean = true,
    val isResyncing: Boolean = false,
    val appTheme: AppTheme = SettingsDefaults.APP_THEME,
    val dynamicColorEnabled: Boolean = SettingsDefaults.DYNAMIC_COLOR_ENABLED,
    val fallbackThemeColor: FallbackThemeColor = SettingsDefaults.FALLBACK_THEME_COLOR,
    val paiScalingFactor: Float = SettingsDefaults.PAI_SCALING_FACTOR,
    val stepGoal: Int = SettingsDefaults.STEP_GOAL,
    val retentionDaysEnabled: Boolean = SettingsDefaults.RETENTION_DAYS_ENABLED,
    val retentionDays: Int = SettingsDefaults.RETENTION_DAYS,
    val trimpModel: TrimpModel = SettingsDefaults.TRIMP_MODEL,
    val banisterMultiplier: Float = PhysiologyProfile.GENERAL.banisterMultiplier,
    val chengBeta: Float = PhysiologyProfile.GENERAL.defaultChengBeta,
    val itrimB: Float = PhysiologyProfile.GENERAL.defaultItrimB,
    val unitSystem: app.readylytics.health.data.preferences.UnitSystem = SettingsDefaults.UNIT_SYSTEM,
    val isCustomPaletteEnabled: Boolean = false,
    val customSecondaryColor: Long = SettingsDefaults.CUSTOM_SECONDARY_COLOR,
    val customTertiaryColor: Long = SettingsDefaults.CUSTOM_TERTIARY_COLOR,
)

data class SyncSettingsState(
    val syncPreference: SyncPreference = SettingsDefaults.SYNC_PREFERENCE,
    val syncIntervalHours: Int = SettingsDefaults.SYNC_INTERVAL_HOURS,
    val isResyncing: Boolean = false,
    val resyncCurrent: Int = 0,
    val resyncTotal: Int = 0,
    val availableDevices: List<String> = emptyList(),
    val primaryDeviceName: String? = null,
    val backgroundSyncEnabled: Boolean = SettingsDefaults.BACKGROUND_SYNC_ENABLED,
    val backgroundSyncIntervalMinutes: Int = SettingsDefaults.BACKGROUND_SYNC_INTERVAL.minutes,
)
