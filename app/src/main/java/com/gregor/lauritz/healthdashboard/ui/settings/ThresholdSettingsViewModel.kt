package com.gregor.lauritz.healthdashboard.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.preferences.CircadianThresholdPreferences
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.domain.circadian.CircadianThresholdValue
import com.gregor.lauritz.healthdashboard.domain.repository.ScoringRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import android.util.Log
import javax.inject.Inject

@HiltViewModel
class ThresholdSettingsViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val scoringRepository: ScoringRepository,
    private val circadianThresholdPreferences: CircadianThresholdPreferences,
) : ViewModel() {

    val uiState: StateFlow<ThresholdSettingsState> = combine(
        settingsRepo.userPreferences,
        circadianThresholdPreferences.overrideMinutesFlow
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
        initialValue = ThresholdSettingsState()
    )

    private val _updatingState = MutableStateFlow(false)
    private val _errorState = MutableStateFlow<String?>(null)

    // We can use another combine to merge internal UI state with the data-driven state
    // But for simplicity, let's just use a separate StateFlow for transient UI state if needed,
    // or merge it into the main uiState if we want a single source of truth.
    
    // Actually, let's keep it simple and just update the main uiState manually for transient states
    // No, stateIn is read-only. We should use a private MutableStateFlow and combine it.

    private val transientState = MutableStateFlow(TransientThresholdState())

    val consolidatedState: StateFlow<ThresholdSettingsState> = combine(
        uiState,
        transientState
    ) { data, transient ->
        data.copy(
            isUpdatingThreshold = transient.isUpdating,
            thresholdError = transient.error
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ThresholdSettingsState()
    )

    fun onEvent(event: SettingsEvent) {
        when (event) {
            is SettingsEvent.HrvOptimalThresholdChanged ->
                viewModelScope.launch { settingsRepo.updateHrvOptimalThreshold(value = event.value) }
            is SettingsEvent.HrvWarningThresholdChanged ->
                viewModelScope.launch { settingsRepo.updateHrvWarningThreshold(value = event.value) }
            is SettingsEvent.RhrOptimalThresholdChanged ->
                viewModelScope.launch { settingsRepo.updateRhrOptimalThreshold(value = event.value) }
            is SettingsEvent.RhrWarningThresholdChanged ->
                viewModelScope.launch { settingsRepo.updateRhrWarningThreshold(value = event.value) }
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
                        validation.onSuccess {
                            transientState.update { it.copy(isUpdating = true, error = null) }
                            circadianThresholdPreferences.setOverride(minutes = event.minutes)
                            scoringRepository.computeAndPersistDailySummary()
                            transientState.update { it.copy(isUpdating = false) }
                        }.onFailure { error ->
                            transientState.update { it.copy(error = "Invalid threshold value. Range: 0-90 minutes.") }
                        }
                    } catch (e: Exception) {
                        Log.e("ThresholdViewModel", "Failed to update threshold", e)
                        try {
                            circadianThresholdPreferences.setOverride(minutes = previousValue)
                        } catch (rollbackError: Exception) {
                            Log.e("ThresholdViewModel", "Rollback failed", rollbackError)
                        }
                        transientState.update {
                            it.copy(isUpdating = false, error = "Failed to update threshold settings. Changes rolled back.")
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
        val error: String? = null
    )
}
