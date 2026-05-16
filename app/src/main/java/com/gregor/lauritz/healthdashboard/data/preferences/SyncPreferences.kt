package com.gregor.lauritz.healthdashboard.data.preferences

import androidx.datastore.core.DataStore
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

internal class SyncPreferences
    @Inject
    constructor(
        private val dataStore: DataStore<UserPreferencesProto>,
    ) {
        suspend fun updateSyncPreference(pref: SyncPreference) {
            dataStore.updateData {
                it
                    .toBuilder()
                    .setSyncPreference(SyncPreferenceProto.valueOf("SYNC_${pref.name}"))
                    .build()
            }
        }

        suspend fun updateSyncIntervalHours(hours: Int) {
            dataStore.updateData { it.toBuilder().setSyncIntervalHours(hours).build() }
        }

        suspend fun updateCircadianThresholdOverride(encryptedMinutes: String?) {
            dataStore.updateData { builder ->
                if (encryptedMinutes != null) {
                    builder.toBuilder().setCircadianThresholdOverride(encryptedMinutes).build()
                } else {
                    builder.toBuilder().clearCircadianThresholdOverride().build()
                }
            }
        }

        suspend fun updateLastSyncTimestamp(timestamp: Long) {
            dataStore.updateData { it.toBuilder().setLastSyncTimestamp(timestamp).build() }
        }

        suspend fun updateInstallDate(date: LocalDate) {
            dataStore.updateData {
                it
                    .toBuilder()
                    .setInstallDate(
                        date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    ).build()
            }
        }

        suspend fun updateInstallDate(dateTimeMs: Long) {
            dataStore.updateData { it.toBuilder().setInstallDate(dateTimeMs).build() }
        }

        suspend fun initializeInstallDateIfUnset() {
            dataStore.updateData { proto ->
                if (proto.installDate == 0L) {
                    proto.toBuilder().setInstallDate(System.currentTimeMillis()).build()
                } else {
                    proto
                }
            }
        }
    }
