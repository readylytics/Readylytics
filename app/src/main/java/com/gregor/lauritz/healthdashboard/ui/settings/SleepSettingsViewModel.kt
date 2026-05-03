package com.gregor.lauritz.healthdashboard.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferencesRepository
import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SleepSettingsViewModel @Inject constructor(
    private val prefsRepo: UserPreferencesRepository,
    private val scoringRepository: ScoringRepository,
) : ViewModel() {

    val uiState: StateFlow<SleepSettingsState> = prefsRepo.userPreferences.map { prefs ->
        SleepSettingsState(
            goalSleepHours = prefs.goalSleepHours,
            hrvBaselineOverride = prefs.hrvBaselineOverride,
            rhrBaselineOverride = prefs.rhrBaselineOverride,
            restingHrBeforeMinutes = prefs.restingHrBeforeMinutes,
            restingHrAfterMinutes = prefs.restingHrAfterMinutes,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SleepSettingsState()
    )

    fun onEvent(event: SettingsEvent) {
        when (event) {
            is SettingsEvent.GoalSleepHoursChanged ->
                viewModelScope.launch {
                    prefsRepo.updateGoalSleepHours(hours = event.hours)
                    scoringRepository.computeAndPersistDailySummary()
                }
            is SettingsEvent.HrvBaselineChanged -> {
                val value = event.text.toIntOrNull()?.toFloat()
                if (value != null) {
                    viewModelScope.launch {
                        prefsRepo.updateHrvBaselineOverride(rmssdMs = value)
                        scoringRepository.computeAndPersistDailySummary()
                    }
                }
            }
            SettingsEvent.HrvBaselineCleared ->
                viewModelScope.launch {
                    prefsRepo.updateHrvBaselineOverride(rmssdMs = null)
                    scoringRepository.computeAndPersistDailySummary()
                }
            is SettingsEvent.RhrBaselineChanged -> {
                val value = event.text.toIntOrNull()?.toFloat()
                if (value != null) {
                    viewModelScope.launch {
                        prefsRepo.updateRhrBaselineOverride(bpm = value)
                        scoringRepository.computeAndPersistDailySummary()
                    }
                }
            }
            SettingsEvent.RhrBaselineCleared ->
                viewModelScope.launch {
                    prefsRepo.updateRhrBaselineOverride(bpm = null)
                    scoringRepository.computeAndPersistDailySummary()
                }
            is SettingsEvent.RestingHrBeforeMinutesChanged ->
                viewModelScope.launch {
                    prefsRepo.updateRestingHrBeforeMinutes(minutes = event.minutes)
                    scoringRepository.computeAndPersistDailySummary()
                }
            is SettingsEvent.RestingHrAfterMinutesChanged ->
                viewModelScope.launch {
                    prefsRepo.updateRestingHrAfterMinutes(minutes = event.minutes)
                    scoringRepository.computeAndPersistDailySummary()
                }
            else -> {}
        }
    }
}
