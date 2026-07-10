package app.readylytics.health.data.preferences

import androidx.datastore.core.DataStore
import app.readylytics.health.domain.preferences.AboutPreferences
import app.readylytics.health.domain.preferences.BackupSettings
import app.readylytics.health.domain.preferences.DeviceSettings
import app.readylytics.health.domain.preferences.DisplaySettings
import app.readylytics.health.domain.preferences.HeartRateZoneSettings
import app.readylytics.health.domain.preferences.PhysiologySettings
import app.readylytics.health.domain.preferences.SleepSettings
import app.readylytics.health.domain.preferences.SyncSettings
import app.readylytics.health.domain.preferences.ThresholdSettings
import app.readylytics.health.domain.preferences.UserPreferencesReader
import app.readylytics.health.domain.scoring.LoadSourceMode
import app.readylytics.health.domain.scoring.TrimpModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository
    @Inject
    internal constructor(
        private val dataStore: DataStore<UserPreferencesProto>,
        private val physiology: PhysiologyPreferences,
        private val thresholds: ThresholdPreferences,
        private val sleep: SleepPreferences,
        private val ui: UIPreferences,
        private val sync: SyncPreferences,
        private val backup: BackupPreferences,
    ) : app.readylytics.health.domain.preferences.SettingsRepository,
        UserPreferencesReader,
        AboutPreferences,
        PhysiologySettings,
        HeartRateZoneSettings,
        SleepSettings,
        ThresholdSettings,
        DisplaySettings,
        SyncSettings,
        DeviceSettings,
        BackupSettings {
        /**
         * The primary flow of user preferences.
         */
        override val userPreferences: Flow<UserPreferences> =
            dataStore.data
                .catch { e ->
                    if (e is IOException) emit(UserPreferencesProto.getDefaultInstance()) else throw e
                }.map { it.toDomainModel() }

        // --- Specific Flows (Legacy from AppConfigRepository) ---

        val syncPreference: Flow<SyncPreference> = userPreferences.map { it.syncPreference }

        val syncIntervalHours: Flow<Int> = userPreferences.map { it.syncIntervalHours }

        val backgroundSyncEnabled: Flow<Boolean> = userPreferences.map { it.backgroundSyncEnabled }

        val backgroundSyncIntervalMinutes: Flow<Int> = userPreferences.map { it.backgroundSyncIntervalMinutes }

        val lastSyncTimestamp: Flow<Long> = userPreferences.map { it.lastSyncTimestamp }

        val appTheme: Flow<AppTheme> = userPreferences.map { it.appTheme }

        val dynamicColorEnabled: Flow<Boolean> = userPreferences.map { it.dynamicColorEnabled }

        val fallbackThemeColor: Flow<FallbackThemeColor> = userPreferences.map { it.fallbackThemeColor }

        val isCustomPaletteEnabled: Flow<Boolean> = userPreferences.map { it.isCustomPaletteEnabled }

        val customSecondaryColor: Flow<Long> = userPreferences.map { it.customSecondaryColor }

        val customTertiaryColor: Flow<Long> = userPreferences.map { it.customTertiaryColor }

        val customPrimaryColor: Flow<Long> = userPreferences.map { it.customPrimaryColor }

        val backupSchedule: Flow<BackupSchedule> = userPreferences.map { it.backupSchedule }

        val lastBackupTimestampFlow: Flow<Long> = userPreferences.map { it.lastBackupTimestamp }

        val backupDirectoryUri: Flow<String?> = userPreferences.map { it.backupDirectoryUri }

        val primaryDeviceName: Flow<String?> = userPreferences.map { it.primaryDeviceName }

        val deviceByDataType: Flow<Map<String, String>> = userPreferences.map { it.deviceByDataType }

        val deviceChangeNoticeDismissed: Flow<Boolean> = userPreferences.map { it.deviceChangeNoticeDismissed }

        // --- Update Methods (delegated to preference modules) ---

        override suspend fun updateGoalSleepHours(hours: Float) = sleep.updateGoalSleepHours(hours)

        override suspend fun updateCoreMergeGapMinutes(minutes: Int) = sleep.updateCoreMergeGapMinutes(minutes)

        override suspend fun updateSupplementalCutoffMinutesOfDay(minutes: Int) =
            sleep.updateSupplementalCutoffMinutesOfDay(minutes)

        override suspend fun updateMinimumCountedSleepSegmentMinutes(minutes: Int) =
            sleep.updateMinimumCountedSleepSegmentMinutes(minutes)

        override suspend fun updateSupplementalArchitectureCoveragePercent(percent: Int) =
            sleep.updateSupplementalArchitectureCoveragePercent(percent)

        override suspend fun updateHrvBaselineOverride(rmssdMs: Float?) = physiology.updateHrvBaselineOverride(rmssdMs)

        override suspend fun updateRhrBaselineOverride(bpm: Float?) = physiology.updateRhrBaselineOverride(bpm)

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

        suspend fun updateAge(age: Int) = physiology.updateAge(age)

        override suspend fun updateBirthday(date: LocalDate) = physiology.updateBirthday(date)

        override suspend fun updateGender(gender: String?) = physiology.updateGender(gender)

        override suspend fun updateHeight(heightCm: Float?) = physiology.updateHeight(heightCm)

        override suspend fun updateHrvOptimalThreshold(value: Float) = thresholds.updateHrvOptimalThreshold(value)

        override suspend fun updateHrvWarningThreshold(value: Float) = thresholds.updateHrvWarningThreshold(value)

        override suspend fun updateRhrOptimalThreshold(value: Float) = thresholds.updateRhrOptimalThreshold(value)

        override suspend fun updateRhrWarningThreshold(value: Float) = thresholds.updateRhrWarningThreshold(value)

        override suspend fun updateRestingHrPercentile(percentile: Int) =
            physiology.updateRestingHrPercentile(percentile)

        override suspend fun updateConsistencyThresholdMinutes(minutes: Int) =
            sleep.updateConsistencyThresholdMinutes(minutes)

        override suspend fun updateConsistencyEvaluationDays(days: Int) = sleep.updateConsistencyEvaluationDays(days)

        override suspend fun updateConsistencyBaselineDays(days: Int) = sleep.updateConsistencyBaselineDays(days)

        override suspend fun updateRasScalingFactor(value: Float) = sleep.updateRasScalingFactor(value)

        override suspend fun updateStepGoal(steps: Int) = sleep.updateStepGoal(steps)

        override suspend fun updateRetentionDaysEnabled(enabled: Boolean) = sleep.updateRetentionDaysEnabled(enabled)

        override suspend fun updateRetentionDays(days: Int) = sleep.updateRetentionDays(days)

        suspend fun updateCollapseHealthConnect(collapsed: Boolean) = ui.updateCollapseHealthConnect(collapsed)

        suspend fun updateCollapseBaselinesThresholds(collapsed: Boolean) =
            ui.updateCollapseBaselinesThresholds(collapsed)

        suspend fun updateCollapseDisplay(collapsed: Boolean) = ui.updateCollapseDisplay(collapsed)

        suspend fun updateCollapseAdvanced(collapsed: Boolean) = ui.updateCollapseAdvanced(collapsed)

        override suspend fun updateAboutDismissed(dismissed: Boolean) = ui.updateAboutDismissed(dismissed)

        override suspend fun updatePhysiologyProfile(profile: PhysiologyProfile) =
            physiology.updatePhysiologyProfile(profile)

        override suspend fun updateTrimpModel(model: TrimpModel) = physiology.updateTrimpModel(model)

        override suspend fun updateBanisterMultiplier(value: Float) = physiology.updateBanisterMultiplier(value)

        override suspend fun updateChengBeta(value: Float) = physiology.updateChengBeta(value)

        override suspend fun updateItrimB(value: Float) = physiology.updateItrimB(value)

        suspend fun updateInstallDate(date: LocalDate) = sync.updateInstallDate(date)

        suspend fun initializeInstallDateIfUnset() = sync.initializeInstallDateIfUnset()

        override suspend fun bootstrapRasSourceModeIfUnset(hasWorkoutOnlyHistory: Boolean) =
            sync.bootstrapRasSourceModeIfUnset(hasWorkoutOnlyHistory)

        suspend fun updateInstallDate(dateTimeMs: Long) = sync.updateInstallDate(dateTimeMs)

        override suspend fun updateSyncPreference(pref: SyncPreference) = sync.updateSyncPreference(pref)

        override suspend fun updateSyncIntervalHours(hours: Int) = sync.updateSyncIntervalHours(hours)

        override suspend fun updateBackgroundSyncEnabled(enabled: Boolean) = sync.updateBackgroundSyncEnabled(enabled)

        override suspend fun updateBackgroundSyncIntervalMinutes(minutes: Int) =
            sync.updateBackgroundSyncIntervalMinutes(minutes)

        suspend fun updateCircadianThresholdOverride(encryptedMinutes: String?) =
            sync.updateCircadianThresholdOverride(encryptedMinutes)

        override suspend fun updateLastSyncTimestamp(timestamp: Long) = sync.updateLastSyncTimestamp(timestamp)

        override suspend fun updateStrainLoadSourceMode(mode: LoadSourceMode) = sync.updateStrainLoadSourceMode(mode)

        override suspend fun updateRasSourceMode(mode: LoadSourceMode) = sync.updateRasSourceMode(mode)

        override suspend fun updateBackupSchedule(schedule: BackupSchedule) = backup.updateBackupSchedule(schedule)

        override suspend fun updateLastBackupTimestamp(timestamp: Long) = backup.updateLastBackupTimestamp(timestamp)

        override suspend fun updateBackupDirectoryUri(uri: String?) = backup.updateBackupDirectoryUri(uri)

        override suspend fun updateBackupPasswordHash(hash: String?) = backup.updateBackupPasswordHash(hash)

        override suspend fun updateAppTheme(theme: AppTheme) = ui.updateAppTheme(theme)

        override suspend fun updateDynamicColorEnabled(enabled: Boolean) = ui.updateDynamicColorEnabled(enabled)

        override suspend fun updateFallbackThemeColor(color: FallbackThemeColor) = ui.updateFallbackThemeColor(color)

        override suspend fun updateCustomPaletteEnabled(enabled: Boolean) = ui.updateCustomPaletteEnabled(enabled)

        override suspend fun updateCustomSecondaryColor(color: Long) = ui.updateCustomSecondaryColor(color)

        override suspend fun updateCustomTertiaryColor(color: Long) = ui.updateCustomTertiaryColor(color)

        override suspend fun updateCustomPrimaryColor(color: Long) = ui.updateCustomPrimaryColor(color)

        override suspend fun updatePrimaryDevice(deviceName: String?) = ui.updatePrimaryDevice(deviceName)

        override suspend fun updateDeviceForDataType(
            dataTypeKey: String,
            deviceLabel: String?,
        ) = ui.updateDeviceForDataType(dataTypeKey, deviceLabel)

        override suspend fun applyDeviceOverrides(overrides: Map<String, String?>) = ui.applyDeviceOverrides(overrides)

        override suspend fun migrateDeviceSelectionIfNeeded() = ui.migrateDeviceSelectionIfNeeded()

        override suspend fun updateDeviceChangeNoticeDismissed(dismissed: Boolean) =
            ui.updateDeviceChangeNoticeDismissed(dismissed)

        override suspend fun getAvailableDevices(): List<String> = ui.getAvailableDevices()

        override suspend fun clearDeviceCache() = ui.clearDeviceCache()

        override suspend fun updateUnitSystem(unitSystem: UnitSystem) = ui.updateUnitSystem(unitSystem)

        override suspend fun updateHrrToleranceSeconds(value: Int) = sleep.updateHrrToleranceSeconds(value)

        suspend fun batchUpdate(block: UserPreferencesProto.Builder.() -> Unit) {
            dataStore.updateData { proto ->
                proto.toBuilder().apply(block).build()
            }
        }
    }
