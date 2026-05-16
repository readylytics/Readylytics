package com.gregor.lauritz.healthdashboard.data.preferences

import androidx.datastore.core.DataStore
import javax.inject.Inject

internal class CloudPreferences
    @Inject
    constructor(
        private val dataStore: DataStore<UserPreferencesProto>,
    ) {
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
                    .setBackupSchedule(
                        when (schedule) {
                            BackupSchedule.MANUAL -> BackupScheduleProto.BACKUP_MANUAL
                            BackupSchedule.DAILY -> BackupScheduleProto.BACKUP_DAILY
                            BackupSchedule.WEEKLY -> BackupScheduleProto.BACKUP_WEEKLY
                        },
                    ).build()
            }
        }

        suspend fun updateLastBackupTimestamp(timestamp: Long) {
            dataStore.updateData { it.toBuilder().setLastBackupTimestamp(timestamp).build() }
        }
    }
