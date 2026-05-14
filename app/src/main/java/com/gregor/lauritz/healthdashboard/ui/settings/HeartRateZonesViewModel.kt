package com.gregor.lauritz.healthdashboard.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.sync.HealthSyncUseCase
import com.gregor.lauritz.healthdashboard.domain.user.UserUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HeartRateZonesViewModel
    @Inject
    constructor(
        private val settingsRepo: SettingsRepository,
        private val userUseCase: UserUseCase,
        private val healthSyncUseCase: HealthSyncUseCase,
    ) : ViewModel() {
        val uiState: StateFlow<HeartRateZonesState> =
            settingsRepo.userPreferences
                .map { prefs ->
                    HeartRateZonesState(
                        maxHeartRate = prefs.maxHeartRate,
                        autoCalculateMaxHr = prefs.autoCalculateMaxHr,
                        manualZoneEditing = prefs.manualZoneEditing,
                        zone1MinPercent = prefs.zone1MinPercent,
                        zone1MaxPercent = prefs.zone1MaxPercent,
                        zone2MaxPercent = prefs.zone2MaxPercent,
                        zone3MaxPercent = prefs.zone3MaxPercent,
                        zone4MaxPercent = prefs.zone4MaxPercent,
                        zone1MinBpm = prefs.zone1MinBpm,
                        zone1MaxBpm = prefs.zone1MaxBpm,
                        zone2MaxBpm = prefs.zone2MaxBpm,
                        zone3MaxBpm = prefs.zone3MaxBpm,
                        zone4MaxBpm = prefs.zone4MaxBpm,
                    )
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5000),
                    initialValue = HeartRateZonesState(),
                )

        fun onEvent(event: SettingsEvent) {
            when (event) {
                is SettingsEvent.MaxHeartRateChanged -> {
                    val value = event.text.toIntOrNull()
                    if (value != null) {
                        viewModelScope.launch {
                            settingsRepo.updateMaxHeartRate(bpm = value)
                            healthSyncUseCase.sync()
                        }
                    }
                }
                is SettingsEvent.AutoCalculateMaxHrChanged -> {
                    viewModelScope.launch {
                        settingsRepo.updateAutoCalculateMaxHr(enabled = event.enabled)
                        if (event.enabled) {
                            userUseCase.calculateAndSetMaxHr()
                        }
                    }
                }
                is SettingsEvent.ManualZoneEditingChanged -> {
                    viewModelScope.launch {
                        settingsRepo.updateManualZoneEditing(enabled = event.enabled)
                    }
                }
                is SettingsEvent.ZonePercentagesChanged -> {
                    viewModelScope.launch {
                        settingsRepo.updateZonePercentages(
                            z1Min = event.z1Min,
                            z1Max = event.z1Max,
                            z2Max = event.z2Max,
                            z3Max = event.z3Max,
                            z4Max = event.z4Max,
                        )
                        healthSyncUseCase.sync()
                    }
                }
                is SettingsEvent.ZoneBpmsChanged -> {
                    viewModelScope.launch {
                        settingsRepo.updateZoneBpms(
                            z1Min = event.z1Min,
                            z1Max = event.z1Max,
                            z2Max = event.z2Max,
                            z3Max = event.z3Max,
                            z4Max = event.z4Max,
                        )
                        healthSyncUseCase.sync()
                    }
                }
                else -> {}
            }
        }
    }
