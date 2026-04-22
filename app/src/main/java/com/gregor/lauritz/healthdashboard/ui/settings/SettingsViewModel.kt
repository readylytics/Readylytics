package com.gregor.lauritz.healthdashboard.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.preferences.SyncPreference
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferencesRepository
import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val goalSleepHours: Float = 8f,
    val hrvBaselineOverride: Float? = null,
    val rhrBaselineOverride: Float? = null,
    val syncPreference: SyncPreference = SyncPreference.BY_TIME,
    val syncIntervalHours: Int = 1,
    val maxHeartRate: Int = 190,
    val hrvOptimalThreshold: Float = 1.00f,
    val hrvWarningThreshold: Float = 0.90f,
    val rhrOptimalThreshold: Float = 0.95f,
    val rhrWarningThreshold: Float = 1.05f,
    val isLoading: Boolean = true,
)

sealed interface SettingsEvent {
    data class GoalSleepHoursChanged(
        val hours: Float,
    ) : SettingsEvent

    data class HrvBaselineChanged(
        val text: String,
    ) : SettingsEvent

    data object HrvBaselineCleared : SettingsEvent

    data class RhrBaselineChanged(
        val text: String,
    ) : SettingsEvent

    data object RhrBaselineCleared : SettingsEvent

    data class SyncPreferenceChanged(
        val pref: SyncPreference,
    ) : SettingsEvent

    data class SyncIntervalChanged(
        val hours: Int,
    ) : SettingsEvent

    data class MaxHeartRateChanged(
        val text: String,
    ) : SettingsEvent

    data class HrvOptimalThresholdChanged(
        val value: Float,
    ) : SettingsEvent

    data class HrvWarningThresholdChanged(
        val value: Float,
    ) : SettingsEvent

    data class RhrOptimalThresholdChanged(
        val value: Float,
    ) : SettingsEvent

    data class RhrWarningThresholdChanged(
        val value: Float,
    ) : SettingsEvent
}

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val prefsRepo: UserPreferencesRepository,
        private val scoringRepository: ScoringRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(SettingsUiState())
        val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

        init {
            viewModelScope.launch {
                prefsRepo.userPreferences.collect { prefs ->
                    _uiState.update {
                        it.copy(
                            goalSleepHours = prefs.goalSleepHours,
                            hrvBaselineOverride = prefs.hrvBaselineOverride,
                            rhrBaselineOverride = prefs.rhrBaselineOverride,
                            syncPreference = prefs.syncPreference,
                            syncIntervalHours = prefs.syncIntervalHours,
                            maxHeartRate = prefs.maxHeartRate,
                            hrvOptimalThreshold = prefs.hrvOptimalThreshold,
                            hrvWarningThreshold = prefs.hrvWarningThreshold,
                            rhrOptimalThreshold = prefs.rhrOptimalThreshold,
                            rhrWarningThreshold = prefs.rhrWarningThreshold,
                            isLoading = false,
                        )
                    }
                }
            }
        }

        fun onEvent(event: SettingsEvent) {
            when (event) {
                is SettingsEvent.GoalSleepHoursChanged ->
                    viewModelScope.launch {
                        prefsRepo.updateGoalSleepHours(event.hours)
                        scoringRepository.computeAndPersistDailySummary()
                    }
                is SettingsEvent.HrvBaselineChanged -> {
                    val value = event.text.toIntOrNull()?.toFloat()
                    if (value != null) {
                        viewModelScope.launch {
                            prefsRepo.updateHrvBaselineOverride(value)
                            scoringRepository.computeAndPersistDailySummary()
                        }
                    }
                }
                SettingsEvent.HrvBaselineCleared ->
                    viewModelScope.launch {
                        prefsRepo.updateHrvBaselineOverride(null)
                        scoringRepository.computeAndPersistDailySummary()
                    }
                is SettingsEvent.RhrBaselineChanged -> {
                    val value = event.text.toIntOrNull()?.toFloat()
                    if (value != null) {
                        viewModelScope.launch {
                            prefsRepo.updateRhrBaselineOverride(value)
                            scoringRepository.computeAndPersistDailySummary()
                        }
                    }
                }
                SettingsEvent.RhrBaselineCleared ->
                    viewModelScope.launch {
                        prefsRepo.updateRhrBaselineOverride(null)
                        scoringRepository.computeAndPersistDailySummary()
                    }
                is SettingsEvent.SyncPreferenceChanged ->
                    viewModelScope.launch {
                        prefsRepo.updateSyncPreference(event.pref)
                    }
                is SettingsEvent.SyncIntervalChanged ->
                    viewModelScope.launch {
                        prefsRepo.updateSyncIntervalHours(event.hours)
                    }
                is SettingsEvent.MaxHeartRateChanged -> {
                    val value = event.text.toIntOrNull()
                    if (value != null) {
                        viewModelScope.launch { prefsRepo.updateMaxHeartRate(value) }
                    }
                }
                is SettingsEvent.HrvOptimalThresholdChanged ->
                    viewModelScope.launch {
                        prefsRepo.updateHrvOptimalThreshold(event.value)
                    }
                is SettingsEvent.HrvWarningThresholdChanged ->
                    viewModelScope.launch {
                        prefsRepo.updateHrvWarningThreshold(event.value)
                    }
                is SettingsEvent.RhrOptimalThresholdChanged ->
                    viewModelScope.launch {
                        prefsRepo.updateRhrOptimalThreshold(event.value)
                    }
                is SettingsEvent.RhrWarningThresholdChanged ->
                    viewModelScope.launch {
                        prefsRepo.updateRhrWarningThreshold(event.value)
                    }
            }
        }
    }
