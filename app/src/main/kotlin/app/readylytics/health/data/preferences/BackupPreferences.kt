package app.readylytics.health.data.preferences

import androidx.datastore.core.DataStore
import javax.inject.Inject

internal class BackupPreferences
    @Inject
    constructor(
        private val dataStore: DataStore<UserPreferencesProto>,
    ) {
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

        suspend fun updateBackupDirectoryUri(uri: String?) {
            dataStore.updateData { builder ->
                if (uri != null) {
                    builder.toBuilder().setBackupDirectoryUri(uri).build()
                } else {
                    builder.toBuilder().clearBackupDirectoryUri().build()
                }
            }
        }

        suspend fun updateBackupPasswordHash(hash: String?) {
            dataStore.updateData { builder ->
                if (hash != null) {
                    builder.toBuilder().setBackupPasswordHash(hash).build()
                } else {
                    builder.toBuilder().clearBackupPasswordHash().build()
                }
            }
        }
    }
