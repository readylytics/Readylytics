package app.readylytics.health.data.preferences

import androidx.datastore.core.DataStore
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

internal class SyncPreferences
    @Inject
    constructor(
        private val dataStore: DataStore<UserPreferencesProto>,
        private val clock: Clock = Clock.systemDefaultZone(),
    ) {
        suspend fun updateSyncPreference(pref: SyncPreference) {
            dataStore.updateData {
                it
                    .toBuilder()
                    .setSyncPreference(
                        when (pref) {
                            SyncPreference.NEVER -> SyncPreferenceProto.SYNC_NEVER
                            SyncPreference.ALWAYS -> SyncPreferenceProto.SYNC_ALWAYS
                            SyncPreference.BY_TIME -> SyncPreferenceProto.SYNC_BY_TIME
                        },
                    ).build()
            }
        }

        suspend fun updateSyncIntervalHours(hours: Int) {
            dataStore.updateData { it.toBuilder().setSyncIntervalHours(hours).build() }
        }

        suspend fun updateBackgroundSyncEnabled(enabled: Boolean) {
            dataStore.updateData { it.toBuilder().setBackgroundSyncEnabled(enabled).build() }
        }

        suspend fun updateBackgroundSyncIntervalMinutes(minutes: Int) {
            dataStore.updateData { it.toBuilder().setBackgroundSyncIntervalMinutes(minutes).build() }
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

        suspend fun updateDriveAccountEmail(email: String?) {
            dataStore.updateData { builder ->
                if (email != null) {
                    builder.toBuilder().setDriveAccountEmail(email).build()
                } else {
                    builder.toBuilder().clearDriveAccountEmail().build()
                }
            }
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
                    proto.toBuilder().setInstallDate(clock.millis()).build()
                } else {
                    proto
                }
            }
        }

        /**
         * One-time bootstrap for existing users' `pai_source_mode` preference.
         *
         * If the field was never explicitly set (proto3 default [LoadSourceModeProto.LOAD_SOURCE_UNSET])
         * and pre-existing workout-only TRIMP history exists, persist [LoadSourceModeProto.LOAD_SOURCE_WORKOUT_ONLY]
         * so existing users keep their prior behavior. Otherwise persist
         * [LoadSourceModeProto.LOAD_SOURCE_EVERYDAY_HEART_RATE] explicitly so the field is no longer
         * unset and this bootstrap never re-runs.
         */
        suspend fun bootstrapPaiSourceModeIfUnset(hasWorkoutOnlyHistory: Boolean) {
            dataStore.updateData { proto ->
                if (proto.paiSourceMode == LoadSourceModeProto.LOAD_SOURCE_UNSET) {
                    val resolved =
                        if (hasWorkoutOnlyHistory) {
                            LoadSourceModeProto.LOAD_SOURCE_WORKOUT_ONLY
                        } else {
                            LoadSourceModeProto.LOAD_SOURCE_EVERYDAY_HEART_RATE
                        }
                    proto.toBuilder().setPaiSourceMode(resolved).build()
                } else {
                    proto
                }
            }
        }
    }
