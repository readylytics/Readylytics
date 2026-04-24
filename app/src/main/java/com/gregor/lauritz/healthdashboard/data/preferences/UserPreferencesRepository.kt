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
import javax.inject.Inject

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

        suspend fun updateSyncPreference(preference: SyncPreference) {
            dataStore.edit { it[Keys.SYNC_PREFERENCE] = preference.name }
        }

        suspend fun updateSyncIntervalHours(hours: Int) {
            dataStore.edit { it[Keys.SYNC_INTERVAL_HOURS] = hours.coerceIn(1, 24) }
        }

        suspend fun updateLastSyncTimestamp(timestampMs: Long) {
            dataStore.edit { it[Keys.LAST_SYNC_TIMESTAMP] = timestampMs }
        }

        suspend fun updateMaxHeartRate(bpm: Int) {
            dataStore.edit { it[Keys.MAX_HEART_RATE] = bpm.coerceIn(150, 220) }
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

        suspend fun updateAppTheme(theme: AppTheme) {
            dataStore.edit { it[Keys.APP_THEME] = theme.name }
        }

        suspend fun updateDriveAccountEmail(email: String?) {
            dataStore.edit { prefs ->
                if (email != null) prefs[Keys.DRIVE_ACCOUNT_EMAIL] = email
                else prefs.remove(Keys.DRIVE_ACCOUNT_EMAIL)
            }
        }

        suspend fun updateBackupSchedule(schedule: BackupSchedule) {
            dataStore.edit { it[Keys.BACKUP_SCHEDULE] = schedule.name }
        }

        suspend fun updateLastBackupTimestamp(ts: Long) {
            dataStore.edit { it[Keys.LAST_BACKUP_TIMESTAMP] = ts }
        }
    }
