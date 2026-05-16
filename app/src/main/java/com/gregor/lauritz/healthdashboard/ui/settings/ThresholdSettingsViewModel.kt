package com.gregor.lauritz.healthdashboard.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.preferences.CircadianThresholdPreferences
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.domain.circadian.CircadianThresholdValue
import com.gregor.lauritz.healthdashboard.domain.repository.ScoringRepository
import com.gregor.lauritz.healthdashboard.domain.validation.SettingsValidators
import com.gregor.lauritz.healthdashboard.domain.validation.ValidationResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ThresholdSettingsViewModel
    @Inject
    constructor(
        private val settingsRepo: SettingsRepository,
        private val scoringRepository: ScoringRepository,
        private val circadianThresholdPreferences: CircadianThresholdPreferences,
    ) : ViewModel() {
        val uiState: StateFlow<ThresholdSettingsState> =
            combine(
                settingsRepo.userPreferences,
                circadianThresholdPreferences.overrideMinutesFlow,
            ) { prefs, decryptedOverride ->
                ThresholdSettingsState(
                    circadianThresholdOverride = decryptedOverride,
                    hrvOptimalThreshold = prefs.hrvOptimalThreshold,
                    hrvWarningThreshold = prefs.hrvWarningThreshold,
                    rhrOptimalThreshold = prefs.rhrOptimalThreshold,
                    rhrWarningThreshold = prefs.rhrWarningThreshold,
                    consistencyThresholdMinutes = prefs.consistencyThresholdMinutes,
                    consistencyEvaluationDays = prefs.consistencyEvaluationDays,
                    consistencyBaselineDays = prefs.consistencyBaselineDays,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = ThresholdSettingsState(),
            )

        private val transientState = MutableStateFlow(TransientThresholdState())

        val consolidatedState: StateFlow<ThresholdSettingsState> =
            combine(
                uiState,
                transientState,
            ) { data, transient ->
                data.copy(
                    isUpdatingThreshold = transient.isUpdating,
                    thresholdError = transient.error,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = ThresholdSettingsState(),
            )

        fun onEvent(event: SettingsEvent) {
            when (event) {
                is SettingsEvent.HrvOptimalThresholdChanged -> {
                    val validation = SettingsValidators.HRV_OPTIMAL_THRESHOLD_RULE.validate(event.value)
                    if (validation is ValidationResult.Valid) {
                        viewModelScope.launch { settingsRepo.updateHrvOptimalThreshold(value = event.value) }
                    }
                }
                is SettingsEvent.HrvWarningThresholdChanged -> {
                    val validation = SettingsValidators.HRV_WARNING_THRESHOLD_RULE.validate(event.value.toString())
                    if (validation is ValidationResult.Valid) {
                        viewModelScope.launch { settingsRepo.updateHrvWarningThreshold(value = event.value) }
                    }
                }
                is SettingsEvent.RhrOptimalThresholdChanged -> {
                    val validation = SettingsValidators.RHR_OPTIMAL_THRESHOLD_RULE.validate(event.value.toString())
                    if (validation is ValidationResult.Valid) {
                        viewModelScope.launch { settingsRepo.updateRhrOptimalThreshold(value = event.value) }
                    }
                }
                is SettingsEvent.RhrWarningThresholdChanged -> {
                    val validation = SettingsValidators.RHR_WARNING_THRESHOLD_RULE.validate(event.value)
                    if (validation is ValidationResult.Valid) {
                        viewModelScope.launch { settingsRepo.updateRhrWarningThreshold(value = event.value) }
                    }
                }
                is SettingsEvent.ConsistencyThresholdChanged ->
                    viewModelScope.launch { settingsRepo.updateConsistencyThresholdMinutes(minutes = event.minutes) }
                is SettingsEvent.ConsistencyEvaluationDaysChanged ->
                    viewModelScope.launch { settingsRepo.updateConsistencyEvaluationDays(days = event.days) }
                is SettingsEvent.ConsistencyBaselineDaysChanged ->
                    viewModelScope.launch { settingsRepo.updateConsistencyBaselineDays(days = event.days) }
                is SettingsEvent.CircadianThresholdOverrideChanged -> {
                    viewModelScope.launch {
                        val previousValue = consolidatedState.value.circadianThresholdOverride
                        try {
                            val validation = CircadianThresholdValue.tryCreate(minutes = event.minutes)
                            validation
                                .onSuccess { _ ->
                                    transientState.update { it.copy(isUpdating = true, error = null) }
                                    circadianThresholdPreferences.setOverride(minutes = event.minutes)
                                    scoringRepository.computeAndPersistDailySummary()
                                    transientState.update { it.copy(isUpdating = false) }
                                }.onFailure { _ ->
                                    transientState.update {
                                        it.copy(
                                            error = "Invalid threshold value. Range: 0-90 minutes.",
                                        )
                                    }
                                }
                        } catch (e: Exception) {
                            Log.e("ThresholdViewModel", "Failed to update threshold", e)
                            try {
                                circadianThresholdPreferences.setOverride(minutes = previousValue)
                            } catch (rollbackError: Exception) {
                                Log.e("ThresholdViewModel", "Rollback failed", rollbackError)
                            }
                            transientState.update {
                                it.copy(
                                    isUpdating = false,
                                    error = "Failed to update threshold settings. Changes rolled back.",
                                )
                            }
                        }
                    }
                }
                SettingsEvent.DismissThresholdError -> {
                    transientState.update { it.copy(error = null) }
                }
                else -> {}
            }
        }

        private data class TransientThresholdState(
            val isUpdating: Boolean = false,
            val error: String? = null,
        )
    }
