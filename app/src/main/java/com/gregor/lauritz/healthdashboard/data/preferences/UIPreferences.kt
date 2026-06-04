package com.gregor.lauritz.healthdashboard.data.preferences

import androidx.datastore.core.DataStore
import com.gregor.lauritz.healthdashboard.data.device.HealthDeviceRepository
import com.gregor.lauritz.healthdashboard.domain.model.HealthDataType
import javax.inject.Inject

internal class UIPreferences
    @Inject
    constructor(
        private val dataStore: DataStore<UserPreferencesProto>,
        private val healthDeviceRepository: HealthDeviceRepository,
    ) {
        suspend fun updateCollapseCloudData(collapsed: Boolean) {
            dataStore.updateData { it.toBuilder().setCollapseCloudData(collapsed).build() }
        }

        suspend fun updateCollapseHealthConnect(collapsed: Boolean) {
            dataStore.updateData { it.toBuilder().setCollapseHealthConnect(collapsed).build() }
        }

        suspend fun updateCollapseBaselinesThresholds(collapsed: Boolean) {
            dataStore.updateData { it.toBuilder().setCollapseBaselinesThresholds(collapsed).build() }
        }

        suspend fun updateCollapseDisplay(collapsed: Boolean) {
            dataStore.updateData { it.toBuilder().setCollapseDisplay(collapsed).build() }
        }

        suspend fun updateCollapseAdvanced(collapsed: Boolean) {
            dataStore.updateData { it.toBuilder().setCollapseAdvanced(collapsed).build() }
        }

        suspend fun updateAboutDismissed(dismissed: Boolean) {
            dataStore.updateData { it.toBuilder().setAboutDismissed(dismissed).build() }
        }

        suspend fun updateAppTheme(theme: AppTheme) {
            dataStore.updateData {
                it
                    .toBuilder()
                    .setAppTheme(
                        when (theme) {
                            AppTheme.SYSTEM -> AppThemeProto.THEME_SYSTEM
                            AppTheme.LIGHT -> AppThemeProto.THEME_LIGHT
                            AppTheme.DARK -> AppThemeProto.THEME_DARK
                        },
                    ).build()
            }
        }

        suspend fun updateDynamicColorEnabled(enabled: Boolean) {
            dataStore.updateData { it.toBuilder().setDynamicColorEnabled(enabled).build() }
        }

        suspend fun updatePrimaryDevice(deviceName: String?) {
            dataStore.updateData { builder ->
                if (deviceName != null) {
                    builder.toBuilder().setPrimaryDeviceName(deviceName).build()
                } else {
                    builder.toBuilder().clearPrimaryDeviceName().build()
                }
            }
        }

        suspend fun updateDeviceForDataType(
            dataTypeKey: String,
            deviceLabel: String?,
        ) {
            dataStore.updateData { proto ->
                val builder = proto.toBuilder()
                if (deviceLabel.isNullOrBlank()) {
                    builder.removeDeviceByDataType(dataTypeKey)
                } else {
                    builder.putDeviceByDataType(dataTypeKey, deviceLabel)
                }
                builder.build()
            }
        }

        /**
         * One-time migration from the legacy single global "primary device" to the
         * per–data-type selection. Seeds every data type with the previously chosen
         * device so existing users keep their behavior, then clears the legacy field.
         */
        suspend fun migrateDeviceSelectionIfNeeded() {
            dataStore.updateData { proto ->
                if (proto.deviceByDataTypeMap.isEmpty() && proto.hasPrimaryDeviceName()) {
                    val builder = proto.toBuilder()
                    HealthDataType.entries.forEach { type ->
                        builder.putDeviceByDataType(type.name, proto.primaryDeviceName)
                    }
                    builder.clearPrimaryDeviceName().build()
                } else {
                    proto
                }
            }
        }

        suspend fun getAvailableDevices(): List<String> = healthDeviceRepository.getAvailableDevices()

        suspend fun clearDeviceCache() {
            healthDeviceRepository.invalidateCache()
        }

        suspend fun updateUnitSystem(unitSystem: UnitSystem) {
            dataStore.updateData {
                it
                    .toBuilder()
                    .setUnitSystem(
                        when (unitSystem) {
                            UnitSystem.METRIC -> UnitSystemProto.UNIT_METRIC
                            UnitSystem.IMPERIAL -> UnitSystemProto.UNIT_IMPERIAL
                        },
                    ).build()
            }
        }
    }
