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
        } ?: SyncPreference.BY_TIME
    }

    val syncIntervalHours: Flow<Int> = dataStore.data.map { prefs ->
        prefs[Keys.SYNC_INTERVAL_HOURS] ?: 1
    }

    val lastSyncTimestamp: Flow<Long> = dataStore.data.map { prefs ->
        prefs[Keys.LAST_SYNC_TIMESTAMP] ?: 0L
    }

    val appTheme: Flow<AppTheme> = dataStore.data.map { prefs ->
        prefs[Keys.APP_THEME]?.let {
            runCatching { AppTheme.valueOf(it) }.getOrNull()
        } ?: AppTheme.SYSTEM
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
