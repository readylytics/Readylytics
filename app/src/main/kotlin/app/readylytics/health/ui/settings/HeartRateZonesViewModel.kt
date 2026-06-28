package app.readylytics.health.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.readylytics.health.domain.model.getOrNull
import app.readylytics.health.domain.preferences.HeartRateZoneSettings
import app.readylytics.health.domain.preferences.UserPreferencesReader
import app.readylytics.health.domain.sync.HealthDataRefresh
import app.readylytics.health.domain.user.UserUseCase
import app.readylytics.health.domain.validation.SettingsValidators
import app.readylytics.health.domain.validation.ValidationResult
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
        private val settingsReader: UserPreferencesReader,
        private val heartRateZoneSettings: HeartRateZoneSettings,
        private val userUseCase: UserUseCase,
        private val healthDataRefresh: HealthDataRefresh,
    ) : ViewModel() {
        val uiState: StateFlow<HeartRateZonesState> =
            settingsReader.userPreferences
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
                    val isValid =
                        SettingsValidators.HEART_RATE_RULE.validate(event.text) is ValidationResult.Valid
                    if (value != null && isValid) {
                        viewModelScope.launch {
                            heartRateZoneSettings.updateMaxHeartRate(bpm = value)
                            healthDataRefresh.refreshAffectedWindow()
                        }
                    }
                }
                is SettingsEvent.AutoCalculateMaxHrChanged -> {
                    viewModelScope.launch {
                        heartRateZoneSettings.updateAutoCalculateMaxHr(enabled = event.enabled)
                        if (event.enabled) {
                            userUseCase.calculateAndSetMaxHr().getOrNull()
                        }
                    }
                }
                is SettingsEvent.ManualZoneEditingChanged -> {
                    viewModelScope.launch {
                        heartRateZoneSettings.updateManualZoneEditing(enabled = event.enabled)
                    }
                }
                is SettingsEvent.ZonePercentagesChanged -> {
                    viewModelScope.launch {
                        heartRateZoneSettings.updateZonePercentages(
                            z1Min = event.z1Min,
                            z1Max = event.z1Max,
                            z2Max = event.z2Max,
                            z3Max = event.z3Max,
                            z4Max = event.z4Max,
                        )
                        healthDataRefresh.refreshAffectedWindow()
                    }
                }
                is SettingsEvent.ZoneBpmsChanged -> {
                    viewModelScope.launch {
                        heartRateZoneSettings.updateZoneBpms(
                            z1Min = event.z1Min,
                            z1Max = event.z1Max,
                            z2Max = event.z2Max,
                            z3Max = event.z3Max,
                            z4Max = event.z4Max,
                        )
                        healthDataRefresh.refreshAffectedWindow()
                    }
                }
                else -> {}
            }
        }
    }
