package com.gregor.lauritz.healthdashboard.ui.settings.cloud

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CloudSettingsViewModel
    @Inject
    constructor(
        private val settingsRepo: SettingsRepository,
    ) : ViewModel() {
        private val _availableDevices = MutableStateFlow<List<String>>(emptyList())
        val availableDevices: StateFlow<List<String>> = _availableDevices.asStateFlow()

        val primaryDevice: StateFlow<String?> =
            settingsRepo.primaryDeviceName
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5000),
                    initialValue = null,
                )

        init {
            refreshAvailableDevices()
        }

        fun refreshAvailableDevices() {
            viewModelScope.launch {
                settingsRepo.clearDeviceCache()
                _availableDevices.value = settingsRepo.getAvailableDevices()
            }
        }

        suspend fun updatePrimaryDevice(deviceName: String?) {
            settingsRepo.updatePrimaryDevice(deviceName)
        }
    }
