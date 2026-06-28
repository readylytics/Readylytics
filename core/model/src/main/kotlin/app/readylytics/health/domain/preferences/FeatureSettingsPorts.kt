package app.readylytics.health.domain.preferences

import app.readylytics.health.data.preferences.AppTheme
import app.readylytics.health.data.preferences.BackupSchedule
import app.readylytics.health.data.preferences.FallbackThemeColor
import app.readylytics.health.data.preferences.PhysiologyProfile
import app.readylytics.health.data.preferences.SyncPreference
import app.readylytics.health.data.preferences.UnitSystem
import app.readylytics.health.domain.scoring.LoadSourceMode
import app.readylytics.health.domain.scoring.TrimpModel
import java.time.LocalDate

interface AboutPreferences {
    suspend fun updateAboutDismissed(dismissed: Boolean)
}

interface PhysiologySettings {
    suspend fun updateBirthday(date: LocalDate)
    suspend fun updateGender(gender: String?)
    suspend fun updateHeight(heightCm: Float?)
    suspend fun updatePhysiologyProfile(profile: PhysiologyProfile)
}

interface HeartRateZoneSettings {
    suspend fun updateMaxHeartRate(bpm: Int)
    suspend fun updateAutoCalculateMaxHr(enabled: Boolean)
    suspend fun updateManualZoneEditing(enabled: Boolean)
    suspend fun updateZonePercentages(
        z1Min: Float,
        z1Max: Float,
        z2Max: Float,
        z3Max: Float,
        z4Max: Float,
    )

    suspend fun updateZoneBpms(
        z1Min: Int,
        z1Max: Int,
        z2Max: Int,
        z3Max: Int,
        z4Max: Int,
    )
}

interface SleepSettings {
    suspend fun updateGoalSleepHours(hours: Float)
    suspend fun updateHrvBaselineOverride(rmssdMs: Float?)
    suspend fun updateRhrBaselineOverride(bpm: Float?)
    suspend fun updateRestingHrPercentile(percentile: Int)
    suspend fun updateStrainLoadSourceMode(mode: LoadSourceMode)
    suspend fun updateRasSourceMode(mode: LoadSourceMode)
}

interface ThresholdSettings {
    suspend fun updateHrvOptimalThreshold(value: Float)
    suspend fun updateHrvWarningThreshold(value: Float)
    suspend fun updateRhrOptimalThreshold(value: Float)
    suspend fun updateRhrWarningThreshold(value: Float)
    suspend fun updateConsistencyThresholdMinutes(minutes: Int)
    suspend fun updateConsistencyEvaluationDays(days: Int)
    suspend fun updateConsistencyBaselineDays(days: Int)
}

interface DisplaySettings {
    suspend fun updateAppTheme(theme: AppTheme)
    suspend fun updateDynamicColorEnabled(enabled: Boolean)
    suspend fun updateFallbackThemeColor(color: FallbackThemeColor)
    suspend fun updateCustomPaletteEnabled(enabled: Boolean)
    suspend fun updateCustomPrimaryColor(color: Long)
    suspend fun updateCustomSecondaryColor(color: Long)
    suspend fun updateCustomTertiaryColor(color: Long)
    suspend fun updateUnitSystem(unitSystem: UnitSystem)
    suspend fun updateRasScalingFactor(value: Float)
    suspend fun updateStepGoal(steps: Int)
    suspend fun updateRetentionDaysEnabled(enabled: Boolean)
    suspend fun updateRetentionDays(days: Int)
    suspend fun updateTrimpModel(model: TrimpModel)
    suspend fun updateBanisterMultiplier(value: Float)
    suspend fun updateChengBeta(value: Float)
    suspend fun updateItrimB(value: Float)
}

interface SyncSettings {
    suspend fun updateSyncPreference(pref: SyncPreference)
    suspend fun updateSyncIntervalHours(hours: Int)
    suspend fun updateBackgroundSyncEnabled(enabled: Boolean)
    suspend fun updateBackgroundSyncIntervalMinutes(minutes: Int)
}

interface DeviceSettings {
    suspend fun getAvailableDevices(): List<String>
    suspend fun clearDeviceCache()
    suspend fun migrateDeviceSelectionIfNeeded()
    suspend fun updatePrimaryDevice(deviceName: String?)
    suspend fun updateDeviceForDataType(dataTypeKey: String, deviceLabel: String?)
    suspend fun updateDeviceChangeNoticeDismissed(dismissed: Boolean)
}

interface BackupSettings {
    suspend fun updateBackupDirectoryUri(uri: String?)
    suspend fun updateBackupPasswordHash(hash: String?)
    suspend fun updateBackupSchedule(schedule: BackupSchedule)
    suspend fun updateLastBackupTimestamp(timestamp: Long)
}
