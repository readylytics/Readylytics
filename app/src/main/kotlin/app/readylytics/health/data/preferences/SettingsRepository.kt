package app.readylytics.health.data.preferences

import androidx.datastore.core.DataStore
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
    ) {
        /**
         * The primary flow of user preferences.
         */
        val userPreferences: Flow<UserPreferences> =
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

        suspend fun updateGoalSleepHours(hours: Float) = sleep.updateGoalSleepHours(hours)

        suspend fun updateHrvBaselineOverride(rmssdMs: Float?) = physiology.updateHrvBaselineOverride(rmssdMs)

        suspend fun updateRhrBaselineOverride(bpm: Float?) = physiology.updateRhrBaselineOverride(bpm)

        suspend fun updateMaxHeartRate(bpm: Int) = physiology.updateMaxHeartRate(bpm)

        suspend fun updateAutoCalculateMaxHr(enabled: Boolean) = physiology.updateAutoCalculateMaxHr(enabled)

        suspend fun updateManualZoneEditing(enabled: Boolean) = physiology.updateManualZoneEditing(enabled)

        suspend fun updateZonePercentages(
            z1Min: Float,
            z1Max: Float,
            z2Max: Float,
            z3Max: Float,
            z4Max: Float,
        ) = physiology.updateZonePercentages(z1Min, z1Max, z2Max, z3Max, z4Max)

        suspend fun updateZoneBpms(
            z1Min: Int,
            z1Max: Int,
            z2Max: Int,
            z3Max: Int,
            z4Max: Int,
        ) = physiology.updateZoneBpms(z1Min, z1Max, z2Max, z3Max, z4Max)

        suspend fun updateAge(age: Int) = physiology.updateAge(age)

        suspend fun updateBirthday(date: LocalDate) = physiology.updateBirthday(date)

        suspend fun updateGender(gender: String?) = physiology.updateGender(gender)

        suspend fun updateHeight(heightCm: Float?) = physiology.updateHeight(heightCm)

        suspend fun updateHrvOptimalThreshold(value: Float) = thresholds.updateHrvOptimalThreshold(value)

        suspend fun updateHrvWarningThreshold(value: Float) = thresholds.updateHrvWarningThreshold(value)

        suspend fun updateRhrOptimalThreshold(value: Float) = thresholds.updateRhrOptimalThreshold(value)

        suspend fun updateRhrWarningThreshold(value: Float) = thresholds.updateRhrWarningThreshold(value)

        suspend fun updateRestingHrPercentile(percentile: Int) = physiology.updateRestingHrPercentile(percentile)

        suspend fun updateConsistencyThresholdMinutes(minutes: Int) = sleep.updateConsistencyThresholdMinutes(minutes)

        suspend fun updateConsistencyEvaluationDays(days: Int) = sleep.updateConsistencyEvaluationDays(days)

        suspend fun updateConsistencyBaselineDays(days: Int) = sleep.updateConsistencyBaselineDays(days)

        suspend fun updateRasScalingFactor(value: Float) = sleep.updateRasScalingFactor(value)

        suspend fun updateStepGoal(steps: Int) = sleep.updateStepGoal(steps)

        suspend fun updateRetentionDaysEnabled(enabled: Boolean) = sleep.updateRetentionDaysEnabled(enabled)

        suspend fun updateRetentionDays(days: Int) = sleep.updateRetentionDays(days)

        suspend fun updateCollapseHealthConnect(collapsed: Boolean) = ui.updateCollapseHealthConnect(collapsed)

        suspend fun updateCollapseBaselinesThresholds(collapsed: Boolean) =
            ui.updateCollapseBaselinesThresholds(collapsed)

        suspend fun updateCollapseDisplay(collapsed: Boolean) = ui.updateCollapseDisplay(collapsed)

        suspend fun updateCollapseAdvanced(collapsed: Boolean) = ui.updateCollapseAdvanced(collapsed)

        suspend fun updateAboutDismissed(dismissed: Boolean) = ui.updateAboutDismissed(dismissed)

        suspend fun updatePhysiologyProfile(profile: PhysiologyProfile) = physiology.updatePhysiologyProfile(profile)

        suspend fun updateTrimpModel(model: TrimpModel) = physiology.updateTrimpModel(model)

        suspend fun updateBanisterMultiplier(value: Float) = physiology.updateBanisterMultiplier(value)

        suspend fun updateChengBeta(value: Float) = physiology.updateChengBeta(value)

        suspend fun updateItrimB(value: Float) = physiology.updateItrimB(value)

        suspend fun updateInstallDate(date: LocalDate) = sync.updateInstallDate(date)

        suspend fun initializeInstallDateIfUnset() = sync.initializeInstallDateIfUnset()

        suspend fun bootstrapRasSourceModeIfUnset(hasWorkoutOnlyHistory: Boolean) =
            sync.bootstrapRasSourceModeIfUnset(hasWorkoutOnlyHistory)

        suspend fun updateInstallDate(dateTimeMs: Long) = sync.updateInstallDate(dateTimeMs)

        suspend fun updateSyncPreference(pref: SyncPreference) = sync.updateSyncPreference(pref)

        suspend fun updateSyncIntervalHours(hours: Int) = sync.updateSyncIntervalHours(hours)

        suspend fun updateBackgroundSyncEnabled(enabled: Boolean) = sync.updateBackgroundSyncEnabled(enabled)

        suspend fun updateBackgroundSyncIntervalMinutes(minutes: Int) =
            sync.updateBackgroundSyncIntervalMinutes(minutes)

        suspend fun updateCircadianThresholdOverride(encryptedMinutes: String?) =
            sync.updateCircadianThresholdOverride(encryptedMinutes)

        suspend fun updateLastSyncTimestamp(timestamp: Long) = sync.updateLastSyncTimestamp(timestamp)

        suspend fun updateStrainLoadSourceMode(mode: LoadSourceMode) = sync.updateStrainLoadSourceMode(mode)

        suspend fun updateRasSourceMode(mode: LoadSourceMode) = sync.updateRasSourceMode(mode)

        suspend fun updateBackupSchedule(schedule: BackupSchedule) = backup.updateBackupSchedule(schedule)

        suspend fun updateLastBackupTimestamp(timestamp: Long) = backup.updateLastBackupTimestamp(timestamp)

        suspend fun updateBackupDirectoryUri(uri: String?) = backup.updateBackupDirectoryUri(uri)

        suspend fun updateBackupPasswordHash(hash: String?) = backup.updateBackupPasswordHash(hash)

        suspend fun updateAppTheme(theme: AppTheme) = ui.updateAppTheme(theme)

        suspend fun updateDynamicColorEnabled(enabled: Boolean) = ui.updateDynamicColorEnabled(enabled)

        suspend fun updateFallbackThemeColor(color: FallbackThemeColor) = ui.updateFallbackThemeColor(color)

        suspend fun updateCustomPaletteEnabled(enabled: Boolean) = ui.updateCustomPaletteEnabled(enabled)

        suspend fun updateCustomSecondaryColor(color: Long) = ui.updateCustomSecondaryColor(color)

        suspend fun updateCustomTertiaryColor(color: Long) = ui.updateCustomTertiaryColor(color)

        suspend fun updateCustomPrimaryColor(color: Long) = ui.updateCustomPrimaryColor(color)

        suspend fun updatePrimaryDevice(deviceName: String?) = ui.updatePrimaryDevice(deviceName)

        suspend fun updateDeviceForDataType(
            dataTypeKey: String,
            deviceLabel: String?,
        ) = ui.updateDeviceForDataType(dataTypeKey, deviceLabel)

        suspend fun migrateDeviceSelectionIfNeeded() = ui.migrateDeviceSelectionIfNeeded()

        suspend fun updateDeviceChangeNoticeDismissed(dismissed: Boolean) =
            ui.updateDeviceChangeNoticeDismissed(dismissed)

        suspend fun getAvailableDevices(): List<String> = ui.getAvailableDevices()

        suspend fun clearDeviceCache() = ui.clearDeviceCache()

        suspend fun updateUnitSystem(unitSystem: UnitSystem) = ui.updateUnitSystem(unitSystem)

        suspend fun batchUpdate(block: UserPreferencesProto.Builder.() -> Unit) {
            dataStore.updateData { proto ->
                proto.toBuilder().apply(block).build()
            }
        }
    }
