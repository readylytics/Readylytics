package com.gregor.lauritz.healthdashboard.ui.settings.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.domain.model.HealthDataType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the per–data-type "Data Sources" settings section. Exposes the list of
 * available source devices (phone, watches, connected apps) and the user's current
 * per-data-type selection, and persists changes through [SettingsRepository].
 */
@HiltViewModel
class DataSourceSettingsViewModel
    @Inject
    constructor(
        private val settingsRepo: SettingsRepository,
    ) : ViewModel() {
        private val _availableDevices = MutableStateFlow<List<String>>(emptyList())
        val availableDevices: StateFlow<List<String>> = _availableDevices.asStateFlow()

        val deviceByDataType: StateFlow<Map<String, String>> =
            settingsRepo.deviceByDataType
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5000),
                    initialValue = emptyMap(),
                )

        init {
            // Make sure any legacy global device selection is reflected per data type.
            viewModelScope.launch { settingsRepo.migrateDeviceSelectionIfNeeded() }
            refreshAvailableDevices()
        }

        fun refreshAvailableDevices() {
            viewModelScope.launch {
                settingsRepo.clearDeviceCache()
                _availableDevices.value = settingsRepo.getAvailableDevices()
            }
        }

        fun updateDevice(
            type: HealthDataType,
            deviceLabel: String?,
        ) {
            viewModelScope.launch {
                settingsRepo.updateDeviceForDataType(type.name, deviceLabel)
            }
        }
    }
