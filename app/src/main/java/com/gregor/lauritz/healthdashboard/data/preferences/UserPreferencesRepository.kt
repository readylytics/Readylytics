package com.gregor.lauritz.healthdashboard.data.preferences

import androidx.datastore.core.DataStore
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
            val SHOW_TRENDS = booleanPreferencesKey("show_trends")
        }

        val userPreferences: Flow<UserPreferences> =
            dataStore.data
                .catch { e ->
                    if (e is IOException) emit(emptyPreferences()) else throw e
                }.map { prefs ->
                    UserPreferences(
                        goalSleepHours = prefs[Keys.GOAL_SLEEP_HOURS] ?: 8f,
                        hrvBaselineOverride =
                            if (prefs[Keys.HRV_BASELINE_OVERRIDE_SET] == true) {
                                prefs[Keys.HRV_BASELINE_OVERRIDE]
                            } else {
                                null
                            },
                        rhrBaselineOverride =
                            if (prefs[Keys.RHR_BASELINE_OVERRIDE_SET] == true) {
                                prefs[Keys.RHR_BASELINE_OVERRIDE]
                            } else {
                                null
                            },
                        syncPreference =
                            prefs[Keys.SYNC_PREFERENCE]
                                ?.let { runCatching { SyncPreference.valueOf(it) }.getOrNull() }
                                ?: SyncPreference.BY_TIME,
                        syncIntervalHours = prefs[Keys.SYNC_INTERVAL_HOURS] ?: 1,
                        lastSyncTimestamp = prefs[Keys.LAST_SYNC_TIMESTAMP] ?: 0L,
                        maxHeartRate = prefs[Keys.MAX_HEART_RATE] ?: 190,
                        autoCalculateMaxHr = prefs[Keys.AUTO_CALCULATE_MAX_HR] ?: true,
                        manualZoneEditing = prefs[Keys.MANUAL_ZONE_EDITING] ?: false,
                        zone1MinPercent = prefs[Keys.ZONE_1_MIN_PERCENT] ?: 0.50f,
                        zone1MaxPercent = prefs[Keys.ZONE_1_MAX_PERCENT] ?: 0.60f,
                        zone2MaxPercent = prefs[Keys.ZONE_2_MAX_PERCENT] ?: 0.70f,
                        zone3MaxPercent = prefs[Keys.ZONE_3_MAX_PERCENT] ?: 0.80f,
                        zone4MaxPercent = prefs[Keys.ZONE_4_MAX_PERCENT] ?: 0.90f,
                        age = prefs[Keys.AGE] ?: 30,
                        birthDay = prefs[Keys.BIRTH_DAY] ?: 1,
                        birthMonth = prefs[Keys.BIRTH_MONTH] ?: 1,
                        birthYear = prefs[Keys.BIRTH_YEAR] ?: 1994,
                        gender = prefs[Keys.GENDER],
                        hrvOptimalThreshold = prefs[Keys.HRV_OPTIMAL_THRESHOLD] ?: 1.00f,
                        hrvWarningThreshold = prefs[Keys.HRV_WARNING_THRESHOLD] ?: 0.90f,
                        rhrOptimalThreshold = prefs[Keys.RHR_OPTIMAL_THRESHOLD] ?: 0.95f,
                        rhrWarningThreshold = prefs[Keys.RHR_WARNING_THRESHOLD] ?: 1.05f,
                        restingHrBeforeMinutes = prefs[Keys.RESTING_HR_BEFORE_MINUTES] ?: 5,
                        restingHrAfterMinutes = prefs[Keys.RESTING_HR_AFTER_MINUTES] ?: 15,
                        appTheme =
                            prefs[Keys.APP_THEME]
                                ?.let { runCatching { AppTheme.valueOf(it) }.getOrNull() }
                                ?: AppTheme.SYSTEM,
                        driveAccountEmail = prefs[Keys.DRIVE_ACCOUNT_EMAIL],
                        backupSchedule =
                            prefs[Keys.BACKUP_SCHEDULE]
                                ?.let { runCatching { BackupSchedule.valueOf(it) }.getOrNull() }
                                ?: BackupSchedule.MANUAL,
                        lastBackupTimestamp = prefs[Keys.LAST_BACKUP_TIMESTAMP] ?: 0L,
                        consistencyThresholdMinutes = prefs[Keys.CONSISTENCY_THRESHOLD_MINUTES] ?: 30,
                        consistencyEvaluationDays = prefs[Keys.CONSISTENCY_EVALUATION_DAYS] ?: 7,
                        consistencyBaselineDays = prefs[Keys.CONSISTENCY_BASELINE_DAYS] ?: 14,
                        paiScalingFactor = prefs[Keys.PAI_SCALING_FACTOR] ?: 0.2f,
                        stepGoal = prefs[Keys.STEP_GOAL] ?: 10000,
                        retentionDaysEnabled = prefs[Keys.RETENTION_DAYS_ENABLED] ?: true,
                        retentionDays = prefs[Keys.RETENTION_DAYS] ?: 365,
                        showTrends = prefs[Keys.SHOW_TRENDS] ?: false,
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
            dataStore.edit { it[Keys.MAX_HEART_RATE] = bpm.coerceIn(100, 250) }
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

        suspend fun updateAge(age: Int) {
            dataStore.edit { it[Keys.AGE] = age.coerceIn(1, 120) }
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
                prefs[Keys.AGE] = age.coerceIn(1, 120)
            }
        }

        suspend fun updateGender(gender: String?) {
            dataStore.edit { prefs ->
                if (gender != null) prefs[Keys.GENDER] = gender
                else prefs.remove(Keys.GENDER)
            }
        }

        suspend fun updateHrvOptimalThreshold(value: Float) {
            dataStore.edit { it[Keys.HRV_OPTIMAL_THRESHOLD] = value.coerceIn(1.0f, 1.2f) }
        }

        suspend fun updateHrvWarningThreshold(value: Float) {
            dataStore.edit { it[Keys.HRV_WARNING_THRESHOLD] = value.coerceIn(0.8f, 1.0f) }
        }

        suspend fun updateRhrOptimalThreshold(value: Float) {
            dataStore.edit { it[Keys.RHR_OPTIMAL_THRESHOLD] = value.coerceIn(0.8f, 1.0f) }
        }

        suspend fun updateRhrWarningThreshold(value: Float) {
            dataStore.edit { it[Keys.RHR_WARNING_THRESHOLD] = value.coerceIn(1.0f, 1.2f) }
        }

        suspend fun updateRestingHrBeforeMinutes(minutes: Int) {
            dataStore.edit { it[Keys.RESTING_HR_BEFORE_MINUTES] = minutes.coerceIn(0, 60) }
        }

        suspend fun updateRestingHrAfterMinutes(minutes: Int) {
            dataStore.edit { it[Keys.RESTING_HR_AFTER_MINUTES] = minutes.coerceIn(0, 60) }
        }

        suspend fun updateConsistencyThresholdMinutes(minutes: Int) {
            dataStore.edit { it[Keys.CONSISTENCY_THRESHOLD_MINUTES] = minutes.coerceIn(0, 90) }
        }

        suspend fun updateConsistencyEvaluationDays(days: Int) {
            dataStore.edit { it[Keys.CONSISTENCY_EVALUATION_DAYS] = days.coerceIn(3, 30) }
        }

        suspend fun updateConsistencyBaselineDays(days: Int) {
            dataStore.edit { it[Keys.CONSISTENCY_BASELINE_DAYS] = days.coerceIn(3, 30) }
        }

        suspend fun updatePaiScalingFactor(value: Float) {
            dataStore.edit { it[Keys.PAI_SCALING_FACTOR] = value.coerceIn(0.1f, 0.3f) }
        }

        suspend fun updateStepGoal(steps: Int) {
            dataStore.edit { it[Keys.STEP_GOAL] = steps.coerceIn(1000, 30000) }
        }

        suspend fun updateRetentionDaysEnabled(enabled: Boolean) {
            dataStore.edit { it[Keys.RETENTION_DAYS_ENABLED] = enabled }
        }

        suspend fun updateRetentionDays(days: Int) {
            dataStore.edit { it[Keys.RETENTION_DAYS] = days.coerceIn(180, 1095) }
        }

        suspend fun updateShowTrends(enabled: Boolean) {
            dataStore.edit { it[Keys.SHOW_TRENDS] = enabled }
        }
    }
