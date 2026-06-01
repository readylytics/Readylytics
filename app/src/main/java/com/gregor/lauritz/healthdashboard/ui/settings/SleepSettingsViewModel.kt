package com.gregor.lauritz.healthdashboard.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.di.ApplicationScope
import com.gregor.lauritz.healthdashboard.domain.repository.ScoringRepository
import com.gregor.lauritz.healthdashboard.domain.validation.SettingsValidators
import com.gregor.lauritz.healthdashboard.domain.validation.ValidationResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SleepSettingsViewModel
    @Inject
    constructor(
        private val settingsRepo: SettingsRepository,
        private val scoringRepository: ScoringRepository,
        @ApplicationScope private val appScope: CoroutineScope,
    ) : ViewModel() {
        val uiState: StateFlow<SleepSettingsState> =
            settingsRepo.userPreferences
                .map { prefs ->
                    SleepSettingsState(
                        goalSleepHours = prefs.goalSleepHours,
                        hrvBaselineOverride = prefs.hrvBaselineOverride,
                        rhrBaselineOverride = prefs.rhrBaselineOverride,
                        restingHrPercentile = prefs.restingHrPercentile,
                    )
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5000),
                    initialValue = SleepSettingsState(),
                )

        fun onEvent(event: SettingsEvent) {
            when (event) {
                is SettingsEvent.GoalSleepHoursChanged ->
                    appScope.launch {
                        settingsRepo.updateGoalSleepHours(hours = event.hours)
                        scoringRepository.computeAndPersistDailySummary()
                    }

                is SettingsEvent.HrvBaselineChanged -> {
                    val value = event.text.toIntOrNull()?.toFloat()
                    val isValid =
                        SettingsValidators.HRV_BASELINE_RULE.validate(event.text) is ValidationResult.Valid
                    if (isValid) {
                        appScope.launch {
                            settingsRepo.updateHrvBaselineOverride(rmssdMs = value)
                            scoringRepository.computeAndPersistDailySummary()
                        }
                    }
                }

                SettingsEvent.HrvBaselineCleared ->
                    appScope.launch {
                        settingsRepo.updateHrvBaselineOverride(rmssdMs = null)
                        scoringRepository.computeAndPersistDailySummary()
                    }

                is SettingsEvent.RhrBaselineChanged -> {
                    val value = event.text.toIntOrNull()?.toFloat()
                    val isValid =
                        SettingsValidators.RHR_BASELINE_RULE.validate(event.text) is ValidationResult.Valid
                    if (isValid) {
                        appScope.launch {
                            settingsRepo.updateRhrBaselineOverride(bpm = value)
                            scoringRepository.computeAndPersistDailySummary()
                        }
                    }
                }

                SettingsEvent.RhrBaselineCleared ->
                    appScope.launch {
                        settingsRepo.updateRhrBaselineOverride(bpm = null)
                        scoringRepository.computeAndPersistDailySummary()
                    }

                is SettingsEvent.RestingHrPercentileChanged -> {
                    val validation =
                        SettingsValidators.RESTING_HR_PERCENTILE_RULE.validate(event.percentile.toString())
                    if (validation is ValidationResult.Valid) {
                        appScope.launch {
                            settingsRepo.updateRestingHrPercentile(percentile = event.percentile)
                            scoringRepository.computeAndPersistDailySummary()
                        }
                    }
                }

                else -> {}
            }
        }
    }
