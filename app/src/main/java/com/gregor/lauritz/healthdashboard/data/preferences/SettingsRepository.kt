package com.gregor.lauritz.healthdashboard.data.preferences

import androidx.datastore.core.DataStore
import com.gregor.lauritz.healthdashboard.data.device.HealthDeviceRepository
import com.gregor.lauritz.healthdashboard.data.local.dao.HeartRateDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HrvDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.data.local.dao.WorkoutDao
import com.gregor.lauritz.healthdashboard.domain.scoring.PaiCalculator
import com.gregor.lauritz.healthdashboard.domain.scoring.TrimpModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository
    @Inject
    constructor(
        private val dataStore: DataStore<UserPreferencesProto>,
        private val sleepSessionDao: SleepSessionDao,
        private val heartRateDao: HeartRateDao,
        private val hrvDao: HrvDao,
        private val workoutDao: WorkoutDao,
        private val healthDeviceRepository: HealthDeviceRepository,
    ) {
        private fun Int.toValidMaxHr() = coerceIn(100, 250)

        private fun Int.toValidAge() = coerceIn(1, 120)

        private fun Float.toValidHrvOptimal() = coerceIn(1.0f, 1.2f)

        private fun Float.toValidHrvWarning() = coerceIn(0.8f, 1.0f)

        private fun Float.toValidRhrOptimal() = coerceIn(0.8f, 1.0f)

        private fun Float.toValidRhrWarning() = coerceIn(1.0f, 1.2f)

        private fun Int.toValidRestMinutes() = coerceIn(0, 60)

        private fun Float.toValidPaiScaling() = coerceIn(0.1f, 0.3f)

        private fun Int.toValidStepGoal() = coerceIn(1000, 30000)

        private fun Int.toValidRetentionDays() = coerceIn(180, 1095)

        private fun Int.toValidConsistencyMinutes() = coerceIn(0, 90)

        private fun Int.toValidConsistencyDays() = coerceIn(3, 30)

        private fun Float.toValidBanisterMultiplier() = coerceIn(0.5f, 2.5f)

        private fun Float.toValidChengBeta() = coerceIn(0.04f, 0.12f)

        private fun Float.toValidItrimB() = coerceIn(1.0f, 4.5f)

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

        val lastSyncTimestamp: Flow<Long> = userPreferences.map { it.lastSyncTimestamp }

        val appTheme: Flow<AppTheme> = userPreferences.map { it.appTheme }

        val dynamicColorEnabled: Flow<Boolean> = userPreferences.map { it.dynamicColorEnabled }

        val driveAccountEmail: Flow<String?> = userPreferences.map { it.driveAccountEmail }

        val backupSchedule: Flow<BackupSchedule> = userPreferences.map { it.backupSchedule }

        val lastBackupTimestampFlow: Flow<Long> = userPreferences.map { it.lastBackupTimestamp }

        val primaryDeviceName: Flow<String?> = userPreferences.map { it.primaryDeviceName }

        // --- Update Methods ---

        suspend fun updateGoalSleepHours(hours: Float) {
            dataStore.updateData { it.toBuilder().setGoalSleepHours(hours).build() }
        }

        suspend fun updateHrvBaselineOverride(rmssdMs: Float?) {
            dataStore.updateData { builder ->
                if (rmssdMs != null) {
                    builder.toBuilder().setHrvBaselineOverride(rmssdMs).build()
                } else {
                    builder.toBuilder().clearHrvBaselineOverride().build()
                }
            }
        }

        suspend fun updateRhrBaselineOverride(bpm: Float?) {
            dataStore.updateData { builder ->
                if (bpm != null) {
                    builder.toBuilder().setRhrBaselineOverride(bpm).build()
                } else {
                    builder.toBuilder().clearRhrBaselineOverride().build()
                }
            }
        }

        suspend fun updateMaxHeartRate(bpm: Int) {
            dataStore.updateData { it.toBuilder().setMaxHeartRate(bpm.toValidMaxHr()).build() }
        }

        suspend fun updateAutoCalculateMaxHr(enabled: Boolean) {
            dataStore.updateData { it.toBuilder().setAutoCalculateMaxHr(enabled).build() }
        }

        suspend fun updateManualZoneEditing(enabled: Boolean) {
            dataStore.updateData { it.toBuilder().setManualZoneEditing(enabled).build() }
        }

        suspend fun updateZonePercentages(
            z1Min: Float,
            z1Max: Float,
            z2Max: Float,
            z3Max: Float,
            z4Max: Float,
        ) {
            dataStore.updateData {
                it
                    .toBuilder()
                    .setZone1MinPercent(z1Min)
                    .setZone1MaxPercent(z1Max)
                    .setZone2MaxPercent(z2Max)
                    .setZone3MaxPercent(z3Max)
                    .setZone4MaxPercent(z4Max)
                    .build()
            }
        }

        suspend fun updateZoneBpms(
            z1Min: Int,
            z1Max: Int,
            z2Max: Int,
            z3Max: Int,
            z4Max: Int,
        ) {
            dataStore.updateData {
                it
                    .toBuilder()
                    .setZone1MinBpm(z1Min)
                    .setZone1MaxBpm(z1Max)
                    .setZone2MaxBpm(z2Max)
                    .setZone3MaxBpm(z3Max)
                    .setZone4MaxBpm(z4Max)
                    .build()
            }
        }

        suspend fun updateAge(age: Int) {
            dataStore.updateData { it.toBuilder().setAge(age.toValidAge()).build() }
        }

        suspend fun updateBirthday(
            day: Int,
            month: Int,
            year: Int,
        ) {
            val safeDay = day.coerceIn(1, 31)
            val safeMonth = month.coerceIn(1, 12)
            val safeYear = year.coerceIn(1900, LocalDate.now().year)

            dataStore.updateData {
                val age = Period.between(LocalDate.of(safeYear, safeMonth, safeDay), LocalDate.now()).years
                it
                    .toBuilder()
                    .setBirthDay(safeDay)
                    .setBirthMonth(safeMonth)
                    .setBirthYear(safeYear)
                    .setAge(age.toValidAge())
                    .build()
            }
        }

        suspend fun updateGender(gender: String?) {
            dataStore.updateData { builder ->
                if (gender != null) {
                    builder.toBuilder().setGender(gender).build()
                } else {
                    builder.toBuilder().clearGender().build()
                }
            }
        }

        suspend fun updateHrvOptimalThreshold(value: Float) {
            dataStore.updateData { it.toBuilder().setHrvOptimalThreshold(value.toValidHrvOptimal()).build() }
        }

        suspend fun updateHrvWarningThreshold(value: Float) {
            dataStore.updateData { it.toBuilder().setHrvWarningThreshold(value.toValidHrvWarning()).build() }
        }

        suspend fun updateRhrOptimalThreshold(value: Float) {
            dataStore.updateData { it.toBuilder().setRhrOptimalThreshold(value.toValidRhrOptimal()).build() }
        }

        suspend fun updateRhrWarningThreshold(value: Float) {
            dataStore.updateData { it.toBuilder().setRhrWarningThreshold(value.toValidRhrWarning()).build() }
        }

        suspend fun updateRestingHrBeforeMinutes(minutes: Int) {
            dataStore.updateData { it.toBuilder().setRestingHrBeforeMinutes(minutes.toValidRestMinutes()).build() }
        }

        suspend fun updateRestingHrAfterMinutes(minutes: Int) {
            dataStore.updateData { it.toBuilder().setRestingHrAfterMinutes(minutes.toValidRestMinutes()).build() }
        }

        suspend fun updateConsistencyThresholdMinutes(minutes: Int) {
            dataStore.updateData {
                it
                    .toBuilder()
                    .setConsistencyThresholdMinutes(
                        minutes.toValidConsistencyMinutes(),
                    ).build()
            }
        }

        suspend fun updateConsistencyEvaluationDays(days: Int) {
            dataStore.updateData { it.toBuilder().setConsistencyEvaluationDays(days.toValidConsistencyDays()).build() }
        }

        suspend fun updateConsistencyBaselineDays(days: Int) {
            dataStore.updateData { it.toBuilder().setConsistencyBaselineDays(days.toValidConsistencyDays()).build() }
        }

        suspend fun updatePaiScalingFactor(value: Float) {
            dataStore.updateData { it.toBuilder().setPaiScalingFactor(value.toValidPaiScaling()).build() }
        }

        suspend fun updateStepGoal(steps: Int) {
            dataStore.updateData { it.toBuilder().setStepGoal(steps.toValidStepGoal()).build() }
        }

        suspend fun updateRetentionDaysEnabled(enabled: Boolean) {
            dataStore.updateData { it.toBuilder().setRetentionDaysEnabled(enabled).build() }
        }

        suspend fun updateRetentionDays(days: Int) {
            dataStore.updateData { it.toBuilder().setRetentionDays(days.toValidRetentionDays()).build() }
        }

        suspend fun updateCollapseCloudData(collapsed: Boolean) {
            dataStore.updateData { it.toBuilder().setCollapseCloudData(collapsed).build() }
        }

        suspend fun updateCollapseHealthConnect(collapsed: Boolean) {
            dataStore.updateData { it.toBuilder().setCollapseHealthConnect(collapsed).build() }
        }

        suspend fun updateCollapseBaselinesThresholds(collapsed: Boolean) {
            dataStore.updateData { it.toBuilder().setCollapseBaselinesThresholds(collapsed).build() }
        }

        suspend fun updateCollapseDisplay(collapsed: Boolean) {
            dataStore.updateData { it.toBuilder().setCollapseDisplay(collapsed).build() }
        }

        suspend fun updateCollapseAdvanced(collapsed: Boolean) {
            dataStore.updateData { it.toBuilder().setCollapseAdvanced(collapsed).build() }
        }

        suspend fun updateAboutDismissed(dismissed: Boolean) {
            dataStore.updateData { it.toBuilder().setAboutDismissed(dismissed).build() }
        }

        suspend fun updatePhysiologyProfile(profile: PhysiologyProfile) {
            val newPaiFactor = PaiCalculator.getDefaultPaiScalingFactor(profile)
            dataStore.updateData {
                it
                    .toBuilder()
                    .setPhysiologyProfile(PhysiologyProfileProto.valueOf("PROFILE_${profile.name}"))
                    .setPaiScalingFactor(newPaiFactor)
                    .setPaiCalibration(profile.banisterMultiplier)
                    .setChengBeta(profile.defaultChengBeta)
                    .setItrimpB(profile.defaultItrimB)
                    .build()
            }
        }

        suspend fun updateTrimpModel(model: TrimpModel) {
            dataStore.updateData {
                it
                    .toBuilder()
                    .setTrimpMethod(
                        when (model) {
                            TrimpModel.BANISTER -> TrimpMethodProto.TRIMP_BANISTER
                            TrimpModel.I_TRIMP -> TrimpMethodProto.TRIMP_ITRIMP
                            TrimpModel.CHENG -> TrimpMethodProto.TRIMP_CHENG
                        },
                    ).build()
            }
        }

        suspend fun updateBanisterMultiplier(value: Float) {
            dataStore.updateData { it.toBuilder().setPaiCalibration(value.toValidBanisterMultiplier()).build() }
        }

        suspend fun updateChengBeta(value: Float) {
            dataStore.updateData { it.toBuilder().setChengBeta(value.toValidChengBeta()).build() }
        }

        suspend fun updateItrimB(value: Float) {
            dataStore.updateData { it.toBuilder().setItrimpB(value.toValidItrimB()).build() }
        }

        suspend fun updateInstallDate(date: LocalDate) {
            dataStore.updateData {
                it
                    .toBuilder()
                    .setInstallDate(
                        date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    ).build()
            }
        }

        suspend fun initializeInstallDateIfUnset() {
            dataStore.updateData { proto ->
                if (proto.installDate == 0L) {
                    proto.toBuilder().setInstallDate(System.currentTimeMillis()).build()
                } else {
                    proto
                }
            }
        }

        suspend fun updateInstallDate(dateTimeMs: Long) {
            dataStore.updateData { it.toBuilder().setInstallDate(dateTimeMs).build() }
        }

        suspend fun updateSyncPreference(pref: SyncPreference) {
            dataStore.updateData {
                it
                    .toBuilder()
                    .setSyncPreference(SyncPreferenceProto.valueOf("SYNC_${pref.name}"))
                    .build()
            }
        }

        suspend fun updateSyncIntervalHours(hours: Int) {
            dataStore.updateData { it.toBuilder().setSyncIntervalHours(hours).build() }
        }

        /**
         * Updates the circadian threshold override.
         * Encryption is handled by the EncryptionManager before calling this method.
         */
        suspend fun updateCircadianThresholdOverride(encryptedMinutes: String?) {
            dataStore.updateData { builder ->
                if (encryptedMinutes != null) {
                    builder.toBuilder().setCircadianThresholdOverride(encryptedMinutes).build()
                } else {
                    builder.toBuilder().clearCircadianThresholdOverride().build()
                }
            }
        }

        suspend fun updateLastSyncTimestamp(timestamp: Long) {
            dataStore.updateData { it.toBuilder().setLastSyncTimestamp(timestamp).build() }
        }

        suspend fun updateDriveAccountEmail(email: String?) {
            dataStore.updateData { builder ->
                if (email != null) {
                    builder.toBuilder().setDriveAccountEmail(email).build()
                } else {
                    builder.toBuilder().clearDriveAccountEmail().build()
                }
            }
        }

        suspend fun updateBackupSchedule(schedule: BackupSchedule) {
            dataStore.updateData {
                it
                    .toBuilder()
                    .setBackupSchedule(BackupScheduleProto.valueOf("BACKUP_${schedule.name}"))
                    .build()
            }
        }

        suspend fun updateLastBackupTimestamp(timestamp: Long) {
            dataStore.updateData { it.toBuilder().setLastBackupTimestamp(timestamp).build() }
        }

        suspend fun updateAppTheme(theme: AppTheme) {
            dataStore.updateData {
                it
                    .toBuilder()
                    .setAppTheme(AppThemeProto.valueOf("THEME_${theme.name}"))
                    .build()
            }
        }

        suspend fun updateDynamicColorEnabled(enabled: Boolean) {
            dataStore.updateData { it.toBuilder().setDynamicColorEnabled(enabled).build() }
        }

        suspend fun updatePrimaryDevice(deviceName: String?) {
            dataStore.updateData { builder ->
                if (deviceName != null) {
                    builder.toBuilder().setPrimaryDeviceName(deviceName).build()
                } else {
                    builder.toBuilder().clearPrimaryDeviceName().build()
                }
            }
        }

        suspend fun getAvailableDevices(): List<String> =
            healthDeviceRepository.getAvailableDevices()

        suspend fun clearDeviceCache() {
            healthDeviceRepository.invalidateCache()
        }
    }
