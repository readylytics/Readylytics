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
    }
