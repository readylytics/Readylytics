package com.gregor.lauritz.healthdashboard.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.core.CorruptionException
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.time.LocalDate
import java.time.Period
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserPreferencesRepository
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) {
        private object Keys {
            val GOAL_SLEEP_HOURS = floatPreferencesKey("goal_sleep_hours")
            val HRV_BASELINE_OVERRIDE = floatPreferencesKey("hrv_baseline_override")
            val HRV_BASELINE_OVERRIDE_SET = booleanPreferencesKey("hrv_baseline_override_set")
            val RHR_BASELINE_OVERRIDE = floatPreferencesKey("rhr_baseline_override")
            val RHR_BASELINE_OVERRIDE_SET = booleanPreferencesKey("rhr_baseline_override_set")
            val SYNC_PREFERENCE = stringPreferencesKey("sync_preference")
            val SYNC_INTERVAL_HOURS = intPreferencesKey("sync_interval_hours")
            val LAST_SYNC_TIMESTAMP = longPreferencesKey("last_sync_timestamp")
            val MAX_HEART_RATE = intPreferencesKey("max_heart_rate")
            val AUTO_CALCULATE_MAX_HR = booleanPreferencesKey("auto_calculate_max_hr")
            val MANUAL_ZONE_EDITING = booleanPreferencesKey("manual_zone_editing")
            val ZONE_1_MIN_PERCENT = floatPreferencesKey("zone_1_min_percent")
            val ZONE_1_MAX_PERCENT = floatPreferencesKey("zone_1_max_percent")
            val ZONE_2_MAX_PERCENT = floatPreferencesKey("zone_2_max_percent")
            val ZONE_3_MAX_PERCENT = floatPreferencesKey("zone_3_max_percent")
            val ZONE_4_MAX_PERCENT = floatPreferencesKey("zone_4_max_percent")
            val ZONE_1_MIN_BPM = intPreferencesKey("zone_1_min_bpm")
            val ZONE_1_MAX_BPM = intPreferencesKey("zone_1_max_bpm")
            val ZONE_2_MAX_BPM = intPreferencesKey("zone_2_max_bpm")
            val ZONE_3_MAX_BPM = intPreferencesKey("zone_3_max_bpm")
            val ZONE_4_MAX_BPM = intPreferencesKey("zone_4_max_bpm")
            val BPM_MIGRATION_DONE = booleanPreferencesKey("bpm_migration_done")
            val AGE = intPreferencesKey("age")
            val BIRTH_DAY = intPreferencesKey("birth_day")
            val BIRTH_MONTH = intPreferencesKey("birth_month")
            val BIRTH_YEAR = intPreferencesKey("birth_year")
            val GENDER = stringPreferencesKey("gender")
            val HRV_OPTIMAL_THRESHOLD = floatPreferencesKey("hrv_optimal_threshold")
            val HRV_WARNING_THRESHOLD = floatPreferencesKey("hrv_warning_threshold")
            val RHR_OPTIMAL_THRESHOLD = floatPreferencesKey("rhr_optimal_threshold")
            val RHR_WARNING_THRESHOLD = floatPreferencesKey("rhr_warning_threshold")
            val RESTING_HR_BEFORE_MINUTES = intPreferencesKey("resting_hr_before_minutes")
            val RESTING_HR_AFTER_MINUTES = intPreferencesKey("resting_hr_after_minutes")
            val APP_THEME = stringPreferencesKey("app_theme")
            val DRIVE_ACCOUNT_EMAIL = stringPreferencesKey("drive_account_email")
            val BACKUP_SCHEDULE = stringPreferencesKey("backup_schedule")
            val LAST_BACKUP_TIMESTAMP = longPreferencesKey("last_backup_timestamp")
            val CONSISTENCY_THRESHOLD_MINUTES = intPreferencesKey("consistency_threshold_minutes")
            val CONSISTENCY_EVALUATION_DAYS = intPreferencesKey("consistency_evaluation_days")
            val CONSISTENCY_BASELINE_DAYS = intPreferencesKey("consistency_baseline_days")
            val PAI_SCALING_FACTOR = floatPreferencesKey("pai_scaling_factor")
            val STEP_GOAL = intPreferencesKey("step_goal")
            val RETENTION_DAYS_ENABLED = booleanPreferencesKey("retention_days_enabled")
            val RETENTION_DAYS = intPreferencesKey("retention_days")
            val COLLAPSE_CLOUD_DATA = booleanPreferencesKey("collapse_cloud_data")
            val COLLAPSE_HEALTH_CONNECT = booleanPreferencesKey("collapse_health_connect")
            val COLLAPSE_BASELINES_THRESHOLDS = booleanPreferencesKey("collapse_baselines_thresholds")
            val COLLAPSE_DISPLAY = booleanPreferencesKey("collapse_display")
            val COLLAPSE_ADVANCED = booleanPreferencesKey("collapse_advanced")
            val ABOUT_DISMISSED = booleanPreferencesKey("about_dismissed")
            val PHYSIOLOGY_PROFILE = stringPreferencesKey("physiology_profile")
            val INSTALL_DATE = longPreferencesKey("install_date")
            val CIRCADIAN_THRESHOLD_OVERRIDE = intPreferencesKey("circadian_threshold_override")
            val CIRCADIAN_THRESHOLD_OVERRIDE_SET = booleanPreferencesKey("circadian_threshold_override_set")
        }

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

        val userPreferences: Flow<UserPreferences> =
            dataStore.data
                .catch { e ->
                    if (e is IOException || e is CorruptionException) emit(emptyPreferences()) else throw e
                }.map { prefs ->
                    val maxHr = prefs[Keys.MAX_HEART_RATE] ?: SettingsDefaults.MAX_HEART_RATE

                    val z1MinBpm = prefs[Keys.ZONE_1_MIN_BPM]
                    val z1MaxBpm = prefs[Keys.ZONE_1_MAX_BPM]
                    val z2MaxBpm = prefs[Keys.ZONE_2_MAX_BPM]
                    val z3MaxBpm = prefs[Keys.ZONE_3_MAX_BPM]
                    val z4MaxBpm = prefs[Keys.ZONE_4_MAX_BPM]

                    val (zone1MinBpm, zone1MaxBpm, zone2MaxBpm, zone3MaxBpm, zone4MaxBpm) =
                        if (z1MinBpm != null && z1MaxBpm != null && z2MaxBpm != null && z3MaxBpm != null && z4MaxBpm != null) {
                            listOf(z1MinBpm, z1MaxBpm, z2MaxBpm, z3MaxBpm, z4MaxBpm)
                        } else {
                            val z1Min = prefs[Keys.ZONE_1_MIN_PERCENT] ?: SettingsDefaults.ZONE_1_MIN_PERCENT
                            val z1Max = prefs[Keys.ZONE_1_MAX_PERCENT] ?: SettingsDefaults.ZONE_1_MAX_PERCENT
                            val z2Max = prefs[Keys.ZONE_2_MAX_PERCENT] ?: SettingsDefaults.ZONE_2_MAX_PERCENT
                            val z3Max = prefs[Keys.ZONE_3_MAX_PERCENT] ?: SettingsDefaults.ZONE_3_MAX_PERCENT
                            val z4Max = prefs[Keys.ZONE_4_MAX_PERCENT] ?: SettingsDefaults.ZONE_4_MAX_PERCENT
                            listOf(
                                (z1Min * maxHr).toInt(),
                                (z1Max * maxHr).toInt(),
                                (z2Max * maxHr).toInt(),
                                (z3Max * maxHr).toInt(),
                                (z4Max * maxHr).toInt()
                            )
                        }

                    UserPreferences(
                        goalSleepHours = prefs[Keys.GOAL_SLEEP_HOURS] ?: SettingsDefaults.GOAL_SLEEP_HOURS,
                        hrvBaselineOverride =
                            if (prefs[Keys.HRV_BASELINE_OVERRIDE_SET] == true) {
                                prefs[Keys.HRV_BASELINE_OVERRIDE]
                            } else {
                                SettingsDefaults.HRV_BASELINE_OVERRIDE
                            },
                        rhrBaselineOverride =
                            if (prefs[Keys.RHR_BASELINE_OVERRIDE_SET] == true) {
                                prefs[Keys.RHR_BASELINE_OVERRIDE]
                            } else {
                                SettingsDefaults.RHR_BASELINE_OVERRIDE
                            },
                        syncPreference =
                            prefs[Keys.SYNC_PREFERENCE]
                                ?.let { runCatching { SyncPreference.valueOf(it) }.getOrNull() }
                                ?: SettingsDefaults.SYNC_PREFERENCE,
                        syncIntervalHours = prefs[Keys.SYNC_INTERVAL_HOURS] ?: SettingsDefaults.SYNC_INTERVAL_HOURS,
                        lastSyncTimestamp = prefs[Keys.LAST_SYNC_TIMESTAMP] ?: SettingsDefaults.LAST_SYNC_TIMESTAMP,
                        maxHeartRate = maxHr,
                        autoCalculateMaxHr = prefs[Keys.AUTO_CALCULATE_MAX_HR] ?: SettingsDefaults.AUTO_CALCULATE_MAX_HR,
                        manualZoneEditing = prefs[Keys.MANUAL_ZONE_EDITING] ?: SettingsDefaults.MANUAL_ZONE_EDITING,
                        zone1MinPercent = prefs[Keys.ZONE_1_MIN_PERCENT] ?: SettingsDefaults.ZONE_1_MIN_PERCENT,
                        zone1MaxPercent = prefs[Keys.ZONE_1_MAX_PERCENT] ?: SettingsDefaults.ZONE_1_MAX_PERCENT,
                        zone2MaxPercent = prefs[Keys.ZONE_2_MAX_PERCENT] ?: SettingsDefaults.ZONE_2_MAX_PERCENT,
                        zone3MaxPercent = prefs[Keys.ZONE_3_MAX_PERCENT] ?: SettingsDefaults.ZONE_3_MAX_PERCENT,
                        zone4MaxPercent = prefs[Keys.ZONE_4_MAX_PERCENT] ?: SettingsDefaults.ZONE_4_MAX_PERCENT,
                        zone1MinBpm = zone1MinBpm,
                        zone1MaxBpm = zone1MaxBpm,
                        zone2MaxBpm = zone2MaxBpm,
                        zone3MaxBpm = zone3MaxBpm,
                        zone4MaxBpm = zone4MaxBpm,
                        age = prefs[Keys.AGE] ?: SettingsDefaults.AGE,
                        birthDay = prefs[Keys.BIRTH_DAY] ?: SettingsDefaults.BIRTH_DAY,
                        birthMonth = prefs[Keys.BIRTH_MONTH] ?: SettingsDefaults.BIRTH_MONTH,
                        birthYear = prefs[Keys.BIRTH_YEAR] ?: SettingsDefaults.BIRTH_YEAR,
                        gender = prefs[Keys.GENDER] ?: SettingsDefaults.GENDER,
                        hrvOptimalThreshold = prefs[Keys.HRV_OPTIMAL_THRESHOLD] ?: SettingsDefaults.HRV_OPTIMAL_THRESHOLD,
                        hrvWarningThreshold = prefs[Keys.HRV_WARNING_THRESHOLD] ?: SettingsDefaults.HRV_WARNING_THRESHOLD,
                        rhrOptimalThreshold = prefs[Keys.RHR_OPTIMAL_THRESHOLD] ?: SettingsDefaults.RHR_OPTIMAL_THRESHOLD,
                        rhrWarningThreshold = prefs[Keys.RHR_WARNING_THRESHOLD] ?: SettingsDefaults.RHR_WARNING_THRESHOLD,
                        restingHrBeforeMinutes = prefs[Keys.RESTING_HR_BEFORE_MINUTES] ?: SettingsDefaults.RESTING_HR_BEFORE_MINUTES,
                        restingHrAfterMinutes = prefs[Keys.RESTING_HR_AFTER_MINUTES] ?: SettingsDefaults.RESTING_HR_AFTER_MINUTES,
                        appTheme =
                            prefs[Keys.APP_THEME]
                                ?.let { runCatching { AppTheme.valueOf(it) }.getOrNull() }
                                ?: SettingsDefaults.APP_THEME,
                        driveAccountEmail = prefs[Keys.DRIVE_ACCOUNT_EMAIL] ?: SettingsDefaults.DRIVE_ACCOUNT_EMAIL,
                        backupSchedule =
                            prefs[Keys.BACKUP_SCHEDULE]
                                ?.let { runCatching { BackupSchedule.valueOf(it) }.getOrNull() }
                                ?: SettingsDefaults.BACKUP_SCHEDULE,
                        lastBackupTimestamp = prefs[Keys.LAST_BACKUP_TIMESTAMP] ?: SettingsDefaults.LAST_BACKUP_TIMESTAMP,
                        consistencyThresholdMinutes = prefs[Keys.CONSISTENCY_THRESHOLD_MINUTES] ?: SettingsDefaults.CONSISTENCY_THRESHOLD_MINUTES,
                        consistencyEvaluationDays = prefs[Keys.CONSISTENCY_EVALUATION_DAYS] ?: SettingsDefaults.CONSISTENCY_EVALUATION_DAYS,
                        consistencyBaselineDays = prefs[Keys.CONSISTENCY_BASELINE_DAYS] ?: SettingsDefaults.CONSISTENCY_BASELINE_DAYS,
                        paiScalingFactor = prefs[Keys.PAI_SCALING_FACTOR] ?: SettingsDefaults.PAI_SCALING_FACTOR,
                        stepGoal = prefs[Keys.STEP_GOAL] ?: SettingsDefaults.STEP_GOAL,
                        retentionDaysEnabled = prefs[Keys.RETENTION_DAYS_ENABLED] ?: SettingsDefaults.RETENTION_DAYS_ENABLED,
                        retentionDays = prefs[Keys.RETENTION_DAYS] ?: SettingsDefaults.RETENTION_DAYS,
                        collapseCloudData = prefs[Keys.COLLAPSE_CLOUD_DATA] ?: SettingsDefaults.COLLAPSE_CLOUD_DATA,
                        collapseHealthConnect = prefs[Keys.COLLAPSE_HEALTH_CONNECT] ?: SettingsDefaults.COLLAPSE_HEALTH_CONNECT,
                        collapseBaselinesThresholds = prefs[Keys.COLLAPSE_BASELINES_THRESHOLDS] ?: SettingsDefaults.COLLAPSE_BASELINES_THRESHOLDS,
                        collapseDisplay = prefs[Keys.COLLAPSE_DISPLAY] ?: SettingsDefaults.COLLAPSE_DISPLAY,
                        collapseAdvanced = prefs[Keys.COLLAPSE_ADVANCED] ?: SettingsDefaults.COLLAPSE_ADVANCED,
                        aboutDismissed = prefs[Keys.ABOUT_DISMISSED] ?: SettingsDefaults.ABOUT_DISMISSED,
                        physiologyProfile = prefs[Keys.PHYSIOLOGY_PROFILE]
                            ?.let { runCatching { PhysiologyProfile.valueOf(it) }.getOrNull() }
                            ?: SettingsDefaults.PHYSIOLOGY_PROFILE,
                        installDate = prefs[Keys.INSTALL_DATE] ?: SettingsDefaults.INSTALL_DATE,
                        circadianThresholdOverride =
                            if (prefs[Keys.CIRCADIAN_THRESHOLD_OVERRIDE_SET] == true) {
                                prefs[Keys.CIRCADIAN_THRESHOLD_OVERRIDE]
                            } else {
                                SettingsDefaults.CIRCADIAN_THRESHOLD_OVERRIDE
                            },
                    )
                }

        suspend fun updateGoalSleepHours(hours: Float) {
            dataStore.edit { it[Keys.GOAL_SLEEP_HOURS] = hours }
        }

        suspend fun updateHrvBaselineOverride(rmssdMs: Float?) {
            dataStore.edit { prefs ->
                if (rmssdMs != null) {
                    prefs[Keys.HRV_BASELINE_OVERRIDE] = rmssdMs
                    prefs[Keys.HRV_BASELINE_OVERRIDE_SET] = true
                } else {
                    prefs.remove(Keys.HRV_BASELINE_OVERRIDE)
                    prefs.remove(Keys.HRV_BASELINE_OVERRIDE_SET)
                }
            }
        }

        suspend fun updateRhrBaselineOverride(bpm: Float?) {
            dataStore.edit { prefs ->
                if (bpm != null) {
                    prefs[Keys.RHR_BASELINE_OVERRIDE] = bpm
                    prefs[Keys.RHR_BASELINE_OVERRIDE_SET] = true
                } else {
                    prefs.remove(Keys.RHR_BASELINE_OVERRIDE)
                    prefs.remove(Keys.RHR_BASELINE_OVERRIDE_SET)
                }
            }
        }

        suspend fun updateMaxHeartRate(bpm: Int) {
            dataStore.edit { it[Keys.MAX_HEART_RATE] = bpm.toValidMaxHr() }
        }

        suspend fun updateAutoCalculateMaxHr(enabled: Boolean) {
            dataStore.edit { it[Keys.AUTO_CALCULATE_MAX_HR] = enabled }
        }

        suspend fun updateManualZoneEditing(enabled: Boolean) {
            dataStore.edit { it[Keys.MANUAL_ZONE_EDITING] = enabled }
        }

        suspend fun updateZonePercentages(
            z1Min: Float,
            z1Max: Float,
            z2Max: Float,
            z3Max: Float,
            z4Max: Float
        ) {
            dataStore.edit { prefs ->
                prefs[Keys.ZONE_1_MIN_PERCENT] = z1Min
                prefs[Keys.ZONE_1_MAX_PERCENT] = z1Max
                prefs[Keys.ZONE_2_MAX_PERCENT] = z2Max
                prefs[Keys.ZONE_3_MAX_PERCENT] = z3Max
                prefs[Keys.ZONE_4_MAX_PERCENT] = z4Max
            }
        }

        suspend fun updateZoneBpms(
            z1Min: Int,
            z1Max: Int,
            z2Max: Int,
            z3Max: Int,
            z4Max: Int
        ) {
            dataStore.edit { prefs ->
                prefs[Keys.ZONE_1_MIN_BPM] = z1Min
                prefs[Keys.ZONE_1_MAX_BPM] = z1Max
                prefs[Keys.ZONE_2_MAX_BPM] = z2Max
                prefs[Keys.ZONE_3_MAX_BPM] = z3Max
                prefs[Keys.ZONE_4_MAX_BPM] = z4Max
            }
        }

        suspend fun updateAge(age: Int) {
            dataStore.edit { it[Keys.AGE] = age.toValidAge() }
        }

        suspend fun updateBirthday(day: Int, month: Int, year: Int) {
            val safeDay = day.coerceIn(1, 31)
            val safeMonth = month.coerceIn(1, 12)
            val safeYear = year.coerceIn(1900, LocalDate.now().year)

            dataStore.edit { prefs ->
                prefs[Keys.BIRTH_DAY] = safeDay
                prefs[Keys.BIRTH_MONTH] = safeMonth
                prefs[Keys.BIRTH_YEAR] = safeYear

                val age = Period.between(LocalDate.of(safeYear, safeMonth, safeDay), LocalDate.now()).years
                prefs[Keys.AGE] = age.toValidAge()
            }
        }

        suspend fun updateGender(gender: String?) {
            dataStore.edit { prefs ->
                if (gender != null) prefs[Keys.GENDER] = gender
                else prefs.remove(Keys.GENDER)
            }
        }

        suspend fun updateHrvOptimalThreshold(value: Float) {
            dataStore.edit { it[Keys.HRV_OPTIMAL_THRESHOLD] = value.toValidHrvOptimal() }
        }

        suspend fun updateHrvWarningThreshold(value: Float) {
            dataStore.edit { it[Keys.HRV_WARNING_THRESHOLD] = value.toValidHrvWarning() }
        }

        suspend fun updateRhrOptimalThreshold(value: Float) {
            dataStore.edit { it[Keys.RHR_OPTIMAL_THRESHOLD] = value.toValidRhrOptimal() }
        }

        suspend fun updateRhrWarningThreshold(value: Float) {
            dataStore.edit { it[Keys.RHR_WARNING_THRESHOLD] = value.toValidRhrWarning() }
        }

        suspend fun updateRestingHrBeforeMinutes(minutes: Int) {
            dataStore.edit { it[Keys.RESTING_HR_BEFORE_MINUTES] = minutes.toValidRestMinutes() }
        }

        suspend fun updateRestingHrAfterMinutes(minutes: Int) {
            dataStore.edit { it[Keys.RESTING_HR_AFTER_MINUTES] = minutes.toValidRestMinutes() }
        }

        suspend fun updateConsistencyThresholdMinutes(minutes: Int) {
            dataStore.edit { it[Keys.CONSISTENCY_THRESHOLD_MINUTES] = minutes.toValidConsistencyMinutes() }
        }

        suspend fun updateConsistencyEvaluationDays(days: Int) {
            dataStore.edit { it[Keys.CONSISTENCY_EVALUATION_DAYS] = days.toValidConsistencyDays() }
        }

        suspend fun updateConsistencyBaselineDays(days: Int) {
            dataStore.edit { it[Keys.CONSISTENCY_BASELINE_DAYS] = days.toValidConsistencyDays() }
        }

        suspend fun updatePaiScalingFactor(value: Float) {
            dataStore.edit { it[Keys.PAI_SCALING_FACTOR] = value.toValidPaiScaling() }
        }

        suspend fun updateStepGoal(steps: Int) {
            dataStore.edit { it[Keys.STEP_GOAL] = steps.toValidStepGoal() }
        }

        suspend fun updateRetentionDaysEnabled(enabled: Boolean) {
            dataStore.edit { it[Keys.RETENTION_DAYS_ENABLED] = enabled }
        }

        suspend fun updateRetentionDays(days: Int) {
            dataStore.edit { it[Keys.RETENTION_DAYS] = days.toValidRetentionDays() }
        }

        suspend fun updateCollapseCloudData(collapsed: Boolean) {
            dataStore.edit { it[Keys.COLLAPSE_CLOUD_DATA] = collapsed }
        }

        suspend fun updateCollapseHealthConnect(collapsed: Boolean) {
            dataStore.edit { it[Keys.COLLAPSE_HEALTH_CONNECT] = collapsed }
        }

        suspend fun updateCollapseBaselinesThresholds(collapsed: Boolean) {
            dataStore.edit { it[Keys.COLLAPSE_BASELINES_THRESHOLDS] = collapsed }
        }

        suspend fun updateCollapseDisplay(collapsed: Boolean) {
            dataStore.edit { it[Keys.COLLAPSE_DISPLAY] = collapsed }
        }

        suspend fun updateCollapseAdvanced(collapsed: Boolean) {
            dataStore.edit { it[Keys.COLLAPSE_ADVANCED] = collapsed }
        }

        suspend fun updateAboutDismissed(dismissed: Boolean) {
            dataStore.edit { it[Keys.ABOUT_DISMISSED] = dismissed }
        }

        suspend fun updatePhysiologyProfile(profile: PhysiologyProfile) {
            dataStore.edit { it[Keys.PHYSIOLOGY_PROFILE] = profile.name }
        }

        suspend fun updateInstallDate(dateTimeMs: Long) {
            dataStore.edit { it[Keys.INSTALL_DATE] = dateTimeMs }
        }

        suspend fun updateCircadianThresholdOverride(minutes: Int?) {
            dataStore.edit { prefs ->
                if (minutes != null) {
                    prefs[Keys.CIRCADIAN_THRESHOLD_OVERRIDE] = minutes
                    prefs[Keys.CIRCADIAN_THRESHOLD_OVERRIDE_SET] = true
                } else {
                    prefs.remove(Keys.CIRCADIAN_THRESHOLD_OVERRIDE)
                    prefs.remove(Keys.CIRCADIAN_THRESHOLD_OVERRIDE_SET)
                }
            }
        }
    }
