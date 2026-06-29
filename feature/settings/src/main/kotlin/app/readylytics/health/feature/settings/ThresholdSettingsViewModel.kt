package app.readylytics.health.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.readylytics.health.core.ui.common.UiText
import app.readylytics.health.domain.circadian.CircadianThresholdValue
import app.readylytics.health.domain.preferences.CircadianThresholdPreferences
import app.readylytics.health.domain.preferences.ThresholdSettings
import app.readylytics.health.domain.preferences.UserPreferencesReader
import app.readylytics.health.domain.repository.ScoringRepository
import app.readylytics.health.domain.util.logE
import app.readylytics.health.domain.validation.SettingsValidators
import app.readylytics.health.domain.validation.ValidationResult
import app.readylytics.health.feature.settings.R
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
        private val settingsReader: UserPreferencesReader,
        private val thresholdSettings: ThresholdSettings,
        private val scoringRepository: ScoringRepository,
        private val circadianThresholdPreferences: CircadianThresholdPreferences,
    ) : ViewModel() {
        val uiState: StateFlow<ThresholdSettingsState> =
            combine(
                settingsReader.userPreferences,
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
                        viewModelScope.launch { thresholdSettings.updateHrvOptimalThreshold(value = event.value) }
                    }
                }
                is SettingsEvent.HrvWarningThresholdChanged -> {
                    val validation = SettingsValidators.HRV_WARNING_THRESHOLD_RULE.validate(event.value)
                    if (validation is ValidationResult.Valid) {
                        viewModelScope.launch { thresholdSettings.updateHrvWarningThreshold(value = event.value) }
                    }
                }
                is SettingsEvent.RhrOptimalThresholdChanged -> {
                    val validation = SettingsValidators.RHR_OPTIMAL_THRESHOLD_RULE.validate(event.value)
                    if (validation is ValidationResult.Valid) {
                        viewModelScope.launch { thresholdSettings.updateRhrOptimalThreshold(value = event.value) }
                    }
                }
                is SettingsEvent.RhrWarningThresholdChanged -> {
                    val validation = SettingsValidators.RHR_WARNING_THRESHOLD_RULE.validate(event.value)
                    if (validation is ValidationResult.Valid) {
                        viewModelScope.launch { thresholdSettings.updateRhrWarningThreshold(value = event.value) }
                    }
                }
                is SettingsEvent.ConsistencyThresholdChanged ->
                    viewModelScope.launch {
                        thresholdSettings.updateConsistencyThresholdMinutes(
                            minutes = event.minutes,
                        )
                    }
                is SettingsEvent.ConsistencyEvaluationDaysChanged ->
                    viewModelScope.launch { thresholdSettings.updateConsistencyEvaluationDays(days = event.days) }
                is SettingsEvent.ConsistencyBaselineDaysChanged ->
                    viewModelScope.launch { thresholdSettings.updateConsistencyBaselineDays(days = event.days) }
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
                                            isUpdating = false,
                                            error = UiText.StringRes(R.string.error_threshold_invalid_range),
                                        )
                                    }
                                }
                        } catch (e: Exception) {
                            logE("ThresholdViewModel", e) { "Failed to update threshold" }
                            try {
                                circadianThresholdPreferences.setOverride(minutes = previousValue)
                            } catch (rollbackError: Exception) {
                                logE("ThresholdViewModel", rollbackError) { "Threshold rollback failed" }
                            }
                            transientState.update {
                                it.copy(
                                    isUpdating = false,
                                    error = UiText.StringRes(R.string.error_threshold_update_failed),
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
            val error: UiText? = null,
        )
    }
