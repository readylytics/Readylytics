package app.readylytics.health.data.preferences

import app.readylytics.health.core.model.data.preferences.BackupSchedule
import app.readylytics.health.core.model.data.preferences.PhysiologyProfile
import app.readylytics.health.core.model.data.preferences.SyncPreference
import app.readylytics.health.core.model.domain.preferences.AboutPreferences
import app.readylytics.health.core.model.domain.preferences.BackupSettings
import app.readylytics.health.core.model.domain.preferences.DeviceSettings
import app.readylytics.health.core.model.domain.preferences.HeartRateZoneSettings
import app.readylytics.health.core.model.domain.preferences.PhysiologySettings
import app.readylytics.health.core.model.domain.preferences.SleepSettings
import app.readylytics.health.core.model.domain.preferences.SyncSettings
import app.readylytics.health.core.model.domain.preferences.ThresholdSettings
import app.readylytics.health.core.model.domain.preferences.Vo2MaxEstimationMethod
import app.readylytics.health.core.model.domain.preferences.Vo2MaxSourceMode
import app.readylytics.health.core.model.domain.scoring.LoadSourceMode
import app.readylytics.health.core.model.domain.scoring.SleepScoreWeightProfile
import java.time.LocalDate

internal class AboutPreferencesDelegate(
    private val ui: UIPreferences,
) : AboutPreferences {
    override suspend fun updateAboutDismissed(dismissed: Boolean) = ui.updateAboutDismissed(dismissed)
}

internal class PhysiologySettingsDelegate(
    private val physiology: PhysiologyPreferences,
) : PhysiologySettings {
    override suspend fun updateBirthday(date: LocalDate) = physiology.updateBirthday(date)

    override suspend fun updateGender(gender: String?) = physiology.updateGender(gender)

    override suspend fun updateHeight(heightCm: Float?) = physiology.updateHeight(heightCm)

    override suspend fun updatePhysiologyProfile(profile: PhysiologyProfile) =
        physiology.updatePhysiologyProfile(profile)

    override suspend fun updateVo2MaxSourceMode(mode: Vo2MaxSourceMode) = physiology.updateVo2MaxSourceMode(mode)

    override suspend fun updateVo2MaxEstimationMethod(method: Vo2MaxEstimationMethod) =
        physiology.updateVo2MaxEstimationMethod(method)
}

internal class HeartRateZoneSettingsDelegate(
    private val physiology: PhysiologyPreferences,
) : HeartRateZoneSettings {
    override suspend fun updateMaxHeartRate(bpm: Int) = physiology.updateMaxHeartRate(bpm)

    override suspend fun updateAutoCalculateMaxHr(enabled: Boolean) = physiology.updateAutoCalculateMaxHr(enabled)

    override suspend fun updateManualZoneEditing(enabled: Boolean) = physiology.updateManualZoneEditing(enabled)

    override suspend fun updateZonePercentages(
        z1Min: Float,
        z1Max: Float,
        z2Max: Float,
        z3Max: Float,
        z4Max: Float,
    ) = physiology.updateZonePercentages(z1Min, z1Max, z2Max, z3Max, z4Max)

    override suspend fun updateZoneBpms(
        z1Min: Int,
        z1Max: Int,
        z2Max: Int,
        z3Max: Int,
        z4Max: Int,
    ) = physiology.updateZoneBpms(z1Min, z1Max, z2Max, z3Max, z4Max)
}

internal interface SleepScheduleSettings {
    suspend fun updateGoalSleepHours(hours: Float)

    suspend fun updateCoreMergeGapMinutes(minutes: Int)

    suspend fun updateSupplementalCutoffMinutesOfDay(minutes: Int)

    suspend fun updateMinimumCountedSleepSegmentMinutes(minutes: Int)

    suspend fun updateSupplementalArchitectureCoveragePercent(percent: Int)

    suspend fun updateSleepScoreWeightProfile(profile: SleepScoreWeightProfile)

    suspend fun updateHypersomniaOnsetPercent(percent: Int)
}

internal interface SleepBaselineAndLoadSettings {
    suspend fun updateHrvBaselineOverride(rmssdMs: Float?)

    suspend fun updateRhrBaselineOverride(bpm: Float?)

    suspend fun updateRestingHrPercentile(percentile: Int)

    suspend fun updateStrainLoadSourceMode(mode: LoadSourceMode)

    suspend fun updateRasSourceMode(mode: LoadSourceMode)
}

internal class SleepScheduleSettingsDelegate(
    private val sleep: SleepPreferences,
) : SleepScheduleSettings {
    override suspend fun updateGoalSleepHours(hours: Float) = sleep.updateGoalSleepHours(hours)

    override suspend fun updateCoreMergeGapMinutes(minutes: Int) = sleep.updateCoreMergeGapMinutes(minutes)

    override suspend fun updateSupplementalCutoffMinutesOfDay(minutes: Int) =
        sleep.updateSupplementalCutoffMinutesOfDay(minutes)

    override suspend fun updateMinimumCountedSleepSegmentMinutes(minutes: Int) =
        sleep.updateMinimumCountedSleepSegmentMinutes(minutes)

    override suspend fun updateSupplementalArchitectureCoveragePercent(percent: Int) =
        sleep.updateSupplementalArchitectureCoveragePercent(percent)

    override suspend fun updateSleepScoreWeightProfile(profile: SleepScoreWeightProfile) =
        sleep.updateSleepScoreWeightProfile(profile)

    override suspend fun updateHypersomniaOnsetPercent(percent: Int) = sleep.updateHypersomniaOnsetPercent(percent)
}

internal class SleepBaselineAndLoadSettingsDelegate(
    private val physiology: PhysiologyPreferences,
    private val sync: SyncPreferences,
) : SleepBaselineAndLoadSettings {
    override suspend fun updateHrvBaselineOverride(rmssdMs: Float?) = physiology.updateHrvBaselineOverride(rmssdMs)

    override suspend fun updateRhrBaselineOverride(bpm: Float?) = physiology.updateRhrBaselineOverride(bpm)

    override suspend fun updateRestingHrPercentile(percentile: Int) = physiology.updateRestingHrPercentile(percentile)

    override suspend fun updateStrainLoadSourceMode(mode: LoadSourceMode) = sync.updateStrainLoadSourceMode(mode)

    override suspend fun updateRasSourceMode(mode: LoadSourceMode) = sync.updateRasSourceMode(mode)
}

internal class SleepSettingsDelegate(
    schedule: SleepScheduleSettings,
    baselineAndLoad: SleepBaselineAndLoadSettings,
) : SleepSettings,
    SleepScheduleSettings by schedule,
    SleepBaselineAndLoadSettings by baselineAndLoad {
    constructor(
        sleep: SleepPreferences,
        physiology: PhysiologyPreferences,
        sync: SyncPreferences,
    ) : this(
        schedule = SleepScheduleSettingsDelegate(sleep),
        baselineAndLoad = SleepBaselineAndLoadSettingsDelegate(physiology, sync),
    )
}

internal class ThresholdSettingsDelegate(
    private val thresholds: ThresholdPreferences,
    private val sleep: SleepPreferences,
) : ThresholdSettings {
    override suspend fun updateHrvOptimalThreshold(value: Float) = thresholds.updateHrvOptimalThreshold(value)

    override suspend fun updateHrvWarningThreshold(value: Float) = thresholds.updateHrvWarningThreshold(value)

    override suspend fun updateRhrOptimalThreshold(value: Float) = thresholds.updateRhrOptimalThreshold(value)

    override suspend fun updateRhrWarningThreshold(value: Float) = thresholds.updateRhrWarningThreshold(value)

    override suspend fun updateBodyTempElevatedThreshold(value: Float) =
        thresholds.updateBodyTempElevatedThreshold(value)

    override suspend fun updateConsistencyThresholdMinutes(minutes: Int) =
        sleep.updateConsistencyThresholdMinutes(minutes)

    override suspend fun updateConsistencyEvaluationDays(days: Int) = sleep.updateConsistencyEvaluationDays(days)

    override suspend fun updateConsistencyBaselineDays(days: Int) = sleep.updateConsistencyBaselineDays(days)
}

internal class SyncSettingsDelegate(
    private val sync: SyncPreferences,
) : SyncSettings {
    override suspend fun updateSyncPreference(pref: SyncPreference) = sync.updateSyncPreference(pref)

    override suspend fun updateSyncIntervalHours(hours: Int) = sync.updateSyncIntervalHours(hours)

    override suspend fun updateBackgroundSyncEnabled(enabled: Boolean) = sync.updateBackgroundSyncEnabled(enabled)

    override suspend fun updateBackgroundSyncIntervalMinutes(minutes: Int) =
        sync.updateBackgroundSyncIntervalMinutes(minutes)
}

internal class DeviceSettingsDelegate(
    private val ui: UIPreferences,
) : DeviceSettings {
    override suspend fun getAvailableDevices(): List<String> = ui.getAvailableDevices()

    override suspend fun clearDeviceCache() = ui.clearDeviceCache()

    override suspend fun migrateDeviceSelectionIfNeeded() = ui.migrateDeviceSelectionIfNeeded()

    override suspend fun updatePrimaryDevice(deviceName: String?) = ui.updatePrimaryDevice(deviceName)

    override suspend fun updateDeviceForDataType(
        dataTypeKey: String,
        deviceLabel: String?,
    ) = ui.updateDeviceForDataType(dataTypeKey, deviceLabel)

    override suspend fun applyDeviceOverrides(overrides: Map<String, String?>) = ui.applyDeviceOverrides(overrides)

    override suspend fun updateDeviceChangeNoticeDismissed(dismissed: Boolean) =
        ui.updateDeviceChangeNoticeDismissed(dismissed)
}

internal class BackupSettingsDelegate(
    private val backup: BackupPreferences,
) : BackupSettings {
    override suspend fun updateBackupDirectoryUri(uri: String?) = backup.updateBackupDirectoryUri(uri)

    override suspend fun updateBackupPasswordHash(hash: String?) = backup.updateBackupPasswordHash(hash)

    override suspend fun updateBackupSchedule(schedule: BackupSchedule) = backup.updateBackupSchedule(schedule)

    override suspend fun updateLastBackupTimestamp(timestamp: Long) = backup.updateLastBackupTimestamp(timestamp)
}

internal interface LegacyPreferences {
    suspend fun updateAge(age: Int)

    suspend fun updateInstallDate(date: LocalDate)

    suspend fun initializeInstallDateIfUnset()

    suspend fun updateInstallDate(dateTimeMs: Long)

    suspend fun updateCircadianThresholdOverride(encryptedMinutes: String?)
}

internal class LegacyPreferencesDelegate(
    private val physiology: PhysiologyPreferences,
    private val sync: SyncPreferences,
) : LegacyPreferences {
    override suspend fun updateAge(age: Int) = physiology.updateAge(age)

    override suspend fun updateInstallDate(date: LocalDate) = sync.updateInstallDate(date)

    override suspend fun initializeInstallDateIfUnset() = sync.initializeInstallDateIfUnset()

    override suspend fun updateInstallDate(dateTimeMs: Long) = sync.updateInstallDate(dateTimeMs)

    override suspend fun updateCircadianThresholdOverride(encryptedMinutes: String?) =
        sync.updateCircadianThresholdOverride(encryptedMinutes)
}
