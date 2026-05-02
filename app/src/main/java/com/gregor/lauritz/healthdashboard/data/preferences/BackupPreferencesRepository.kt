package com.gregor.lauritz.healthdashboard.data.preferences

import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupPreferencesRepository @Inject constructor(
    private val dataStore: DataStore<UserPreferencesProto>
) {
    val driveAccountEmail: Flow<String?> = dataStore.data.map { 
        if (it.hasDriveAccountEmail()) it.driveAccountEmail else null
    }

    val backupSchedule: Flow<BackupSchedule> = dataStore.data.map { 
        BackupSchedule.valueOf(it.backupSchedule.name.removePrefix("BACKUP_"))
    }

    val lastBackupTimestamp: Flow<Long> = dataStore.data.map { it.lastBackupTimestamp }

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
            it.toBuilder()
                .setBackupSchedule(BackupScheduleProto.valueOf("BACKUP_${schedule.name}"))
                .build()
        }
    }

    suspend fun updateLastBackupTimestamp(ts: Long) {
        dataStore.updateData { it.toBuilder().setLastBackupTimestamp(ts).build() }
    }
}
