package com.gregor.lauritz.healthdashboard.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.preferences.SyncPreference
import com.gregor.lauritz.healthdashboard.domain.sync.HealthSyncUseCase
import com.gregor.lauritz.healthdashboard.domain.sync.ResyncHealthConnectUseCase
import com.gregor.lauritz.healthdashboard.domain.util.logE
import com.gregor.lauritz.healthdashboard.domain.validation.SettingsValidators
import com.gregor.lauritz.healthdashboard.domain.validation.ValidationResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SyncSettingsViewModel
    @Inject
    constructor(
        private val settingsRepo: SettingsRepository,
        private val healthSyncUseCase: HealthSyncUseCase,
        private val resyncHealthConnectUseCase: ResyncHealthConnectUseCase,
    ) : ViewModel() {
        private val _isResyncing = MutableStateFlow(false)
        val isResyncing: StateFlow<Boolean> = _isResyncing.asStateFlow()

        private val availableDevices = MutableStateFlow<List<String>>(emptyList())

        // Internal property to allow overriding in tests
        var sharingStarted: SharingStarted = SharingStarted.WhileSubscribed(5000)

        val uiState: StateFlow<SyncSettingsState> by lazy {
            combine(
                settingsRepo.userPreferences,
                _isResyncing,
                availableDevices,
            ) { prefs, isResyncing, availableDevices ->
                SyncSettingsState(
                    syncPreference = prefs.syncPreference,
                    syncIntervalHours = prefs.syncIntervalHours,
                    isResyncing = isResyncing,
                    availableDevices = availableDevices,
                    primaryDeviceName = prefs.primaryDeviceName,
                )
            }.stateIn(
                scope = viewModelScope,
                started = sharingStarted,
                initialValue = SyncSettingsState(),
            )
        }

        init {
            loadAvailableDevices()
        }

        private fun loadAvailableDevices() {
            viewModelScope.launch {
                try {
                    val devices = settingsRepo.getAvailableDevices()
                    availableDevices.value = devices
                } catch (e: Exception) {
                    logE("SyncSettingsViewModel", e) { "Failed to load available devices" }
                }
            }
        }

        fun onEvent(event: SettingsEvent) {
            when (event) {
                is SettingsEvent.SyncPreferenceChanged -> {
                    viewModelScope.launch {
                        settingsRepo.updateSyncPreference(pref = event.pref)
                        if (event.pref == SyncPreference.ALWAYS) {
                            healthSyncUseCase.sync()
                        }
                    }
                }
                is SettingsEvent.SyncIntervalChanged -> {
                    val validation = SettingsValidators.SYNC_INTERVAL_HOURS_RULE.validate(event.hours.toString())
                    if (validation is ValidationResult.Valid) {
                        viewModelScope.launch {
                            settingsRepo.updateSyncIntervalHours(hours = event.hours)
                        }
                    }
                }
                SettingsEvent.ResyncHealthConnect -> {
                    viewModelScope.launch {
                        _isResyncing.value = true
                        try {
                            resyncHealthConnectUseCase.execute()
                        } finally {
                            _isResyncing.value = false
                        }
                    }
                }
                else -> {}
            }
        }
    }
