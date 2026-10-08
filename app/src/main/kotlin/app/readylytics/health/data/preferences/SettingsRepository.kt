package app.readylytics.health.data.preferences

import androidx.datastore.core.DataStore
import app.readylytics.health.core.model.data.preferences.AppTheme
import app.readylytics.health.core.model.data.preferences.BackupSchedule
import app.readylytics.health.core.model.data.preferences.FallbackThemeColor
import app.readylytics.health.core.model.data.preferences.SyncPreference
import app.readylytics.health.core.model.domain.preferences.AboutPreferences
import app.readylytics.health.core.model.domain.preferences.BackupSettings
import app.readylytics.health.core.model.domain.preferences.DeviceSettings
import app.readylytics.health.core.model.domain.preferences.DisplaySettings
import app.readylytics.health.core.model.domain.preferences.HeartRateZoneSettings
import app.readylytics.health.core.model.domain.preferences.PhysiologySettings
import app.readylytics.health.core.model.domain.preferences.SleepSettings
import app.readylytics.health.core.model.domain.preferences.SyncSettings
import app.readylytics.health.core.model.domain.preferences.ThresholdSettings
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.preferences.UserPreferencesReader
import app.readylytics.health.core.model.domain.scoring.SleepScoreWeightProfile
import app.readylytics.health.core.model.domain.scoring.TrainingReadinessConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.time.Clock
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
        private val clock: Clock,
    ) : app.readylytics.health.core.model.domain.preferences.SettingsRepository,
        UserPreferencesReader,
        AboutPreferences by AboutPreferencesDelegate(ui),
        PhysiologySettings by PhysiologySettingsDelegate(physiology),
        HeartRateZoneSettings by HeartRateZoneSettingsDelegate(physiology),
        SleepSettings by SleepSettingsDelegate(sleep, physiology, sync),
        ThresholdSettings by ThresholdSettingsDelegate(thresholds, sleep),
        DisplaySettings by DisplaySettingsDelegate(ui, sleep, physiology),
        SyncSettings by SyncSettingsDelegate(sync),
        DeviceSettings by DeviceSettingsDelegate(ui),
        BackupSettings by BackupSettingsDelegate(backup),
        LegacyPreferences by LegacyPreferencesDelegate(physiology, sync) {
        /**
         * The primary flow of user preferences.
         */
        override val userPreferences: Flow<UserPreferences> =
            dataStore.data
                .catch { e ->
                    if (e is IOException) emit(UserPreferencesProto.getDefaultInstance()) else throw e
                }.map { it.toDomainModel(clock) }

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

        // --- Explicit core repository and overlapping methods ---

        override suspend fun bootstrapRasSourceModeIfUnset(hasWorkoutOnlyHistory: Boolean) =
            sync.bootstrapRasSourceModeIfUnset(hasWorkoutOnlyHistory)

        override suspend fun updateMaxHeartRate(bpm: Int) = physiology.updateMaxHeartRate(bpm)

        override suspend fun migrateDeviceSelectionIfNeeded() = ui.migrateDeviceSelectionIfNeeded()

        override suspend fun updateLastSyncTimestamp(timestamp: Long) = sync.updateLastSyncTimestamp(timestamp)

        override suspend fun updateBirthday(date: LocalDate) = physiology.updateBirthday(date)

        override suspend fun updateScoringVersion(version: Int) = sleep.updateScoringVersion(version)

        override suspend fun updateSelectedWorkoutRepairCompleted(completed: Boolean) =
            sync.updateSelectedWorkoutRepairCompleted(completed)

        override suspend fun updateSleepScoreRecalcBaseline(
            weightProfile: SleepScoreWeightProfile,
            goalSleepHours: Float,
            hypersomniaOnsetPercent: Int,
        ) = sleep.updateSleepScoreRecalcBaseline(weightProfile, goalSleepHours, hypersomniaOnsetPercent)

        override suspend fun updateTrainingReadinessConfig(config: TrainingReadinessConfig) {
            dataStore.updateData { proto ->
                proto
                    .toBuilder()
                    .setLastAppliedTrainingReadinessResidualFatigueScale(config.residualFatigueScale)
                    .setLastAppliedTrainingReadinessLoadBalanceWeight(config.loadBalanceWeight)
                    .build()
            }
        }

        // --- Legacy methods ---

        suspend fun batchUpdate(block: UserPreferencesProto.Builder.() -> Unit) {
            dataStore.updateData { proto ->
                proto.toBuilder().apply(block).build()
            }
        }
    }
