package com.gregor.lauritz.healthdashboard.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppConfigRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    private object Keys {
        val SYNC_PREFERENCE = stringPreferencesKey("sync_preference")
        val SYNC_INTERVAL_HOURS = intPreferencesKey("sync_interval_hours")
        val LAST_SYNC_TIMESTAMP = longPreferencesKey("last_sync_timestamp")
        val APP_THEME = stringPreferencesKey("app_theme")
    }

    val syncPreference: Flow<SyncPreference> = dataStore.data.map { prefs ->
        prefs[Keys.SYNC_PREFERENCE]?.let {
            runCatching { SyncPreference.valueOf(it) }.getOrNull()
        } ?: SettingsDefaults.SYNC_PREFERENCE
    }

    val syncIntervalHours: Flow<Int> = dataStore.data.map { prefs ->
        prefs[Keys.SYNC_INTERVAL_HOURS] ?: SettingsDefaults.SYNC_INTERVAL_HOURS
    }

    val lastSyncTimestamp: Flow<Long> = dataStore.data.map { prefs ->
        prefs[Keys.LAST_SYNC_TIMESTAMP] ?: SettingsDefaults.LAST_SYNC_TIMESTAMP
    }

    val appTheme: Flow<AppTheme> = dataStore.data.map { prefs ->
        prefs[Keys.APP_THEME]?.let {
            runCatching { AppTheme.valueOf(it) }.getOrNull()
        } ?: SettingsDefaults.APP_THEME
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

    suspend fun updateAppTheme(theme: AppTheme) {
        dataStore.edit { it[Keys.APP_THEME] = theme.name }
    }
}
