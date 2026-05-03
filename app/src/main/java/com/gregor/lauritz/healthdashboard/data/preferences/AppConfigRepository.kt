package com.gregor.lauritz.healthdashboard.data.preferences

import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppConfigRepository @Inject constructor(
    private val dataStore: DataStore<UserPreferencesProto>
) {
    val syncPreference: Flow<SyncPreference> = dataStore.data.map { 
        SyncPreference.valueOf(it.syncPreference.name.removePrefix("SYNC_"))
    }

    val syncIntervalHours: Flow<Int> = dataStore.data.map { it.syncIntervalHours }

    val lastSyncTimestamp: Flow<Long> = dataStore.data.map { it.lastSyncTimestamp }

    val appTheme: Flow<AppTheme> = dataStore.data.map { 
        AppTheme.valueOf(it.appTheme.name.removePrefix("THEME_"))
    }

    val dynamicColorEnabled: Flow<Boolean> = dataStore.data.map { it.dynamicColorEnabled }

    suspend fun updateSyncPreference(preference: SyncPreference) {
        dataStore.updateData { 
            it.toBuilder()
                .setSyncPreference(SyncPreferenceProto.valueOf("SYNC_${preference.name}"))
                .build()
        }
    }

    suspend fun updateSyncIntervalHours(hours: Int) {
        dataStore.updateData { it.toBuilder().setSyncIntervalHours(hours).build() }
    }

    suspend fun updateLastSyncTimestamp(timestampMs: Long) {
        dataStore.updateData { it.toBuilder().setLastSyncTimestamp(timestampMs).build() }
    }

    suspend fun updateAppTheme(theme: AppTheme) {
        dataStore.updateData { 
            it.toBuilder()
                .setAppTheme(AppThemeProto.valueOf("THEME_${theme.name}"))
                .build()
        }
    }

    suspend fun updateDynamicColorEnabled(enabled: Boolean) {
        dataStore.updateData { it.toBuilder().setDynamicColorEnabled(enabled).build() }
    }
}
