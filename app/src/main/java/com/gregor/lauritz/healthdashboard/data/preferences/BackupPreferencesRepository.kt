package com.gregor.lauritz.healthdashboard.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupPreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    private object Keys {
        val DRIVE_ACCOUNT_EMAIL = stringPreferencesKey("drive_account_email")
        val BACKUP_SCHEDULE = stringPreferencesKey("backup_schedule")
        val LAST_BACKUP_TIMESTAMP = longPreferencesKey("last_backup_timestamp")
    }

    val driveAccountEmail: Flow<String?> = dataStore.data.map { prefs ->
        prefs[Keys.DRIVE_ACCOUNT_EMAIL]
    }

    val backupSchedule: Flow<BackupSchedule> = dataStore.data.map { prefs ->
        prefs[Keys.BACKUP_SCHEDULE]?.let {
            runCatching { BackupSchedule.valueOf(it) }.getOrNull()
        } ?: BackupSchedule.MANUAL
    }

    val lastBackupTimestamp: Flow<Long> = dataStore.data.map { prefs ->
        prefs[Keys.LAST_BACKUP_TIMESTAMP] ?: 0L
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
