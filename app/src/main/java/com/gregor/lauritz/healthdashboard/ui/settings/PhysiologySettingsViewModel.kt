package com.gregor.lauritz.healthdashboard.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.domain.scoring.PaiCalculator
import com.gregor.lauritz.healthdashboard.domain.sync.HealthSyncUseCase
import com.gregor.lauritz.healthdashboard.domain.user.UserUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PhysiologySettingsViewModel
    @Inject
    constructor(
        private val settingsRepo: SettingsRepository,
        private val userUseCase: UserUseCase,
        private val healthSyncUseCase: HealthSyncUseCase,
    ) : ViewModel() {
        val uiState: StateFlow<PhysiologySettingsState> =
            settingsRepo.userPreferences
                .map { prefs ->
                    PhysiologySettingsState(
                        physiologyProfile = prefs.physiologyProfile,
                        age = prefs.age,
                        birthDay = prefs.birthDay,
                        birthMonth = prefs.birthMonth,
                        birthYear = prefs.birthYear,
                        gender = prefs.gender,
                    )
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5000),
                    initialValue = PhysiologySettingsState(),
                )

        fun onEvent(event: SettingsEvent) {
            when (event) {
                is SettingsEvent.BirthdayChanged -> {
                    viewModelScope.launch {
                        userUseCase.updateBirthday(day = event.day, month = event.month, year = event.year)
                        healthSyncUseCase.sync()
                    }
                }
                is SettingsEvent.GenderChanged -> {
                    viewModelScope.launch {
                        settingsRepo.updateGender(gender = event.gender?.displayName)
                        healthSyncUseCase.sync()
                    }
                }
                is SettingsEvent.PhysiologyProfileChanged ->
                    viewModelScope.launch {
                        settingsRepo.updatePhysiologyProfile(profile = event.profile)
                        healthSyncUseCase.sync()
                    }
                SettingsEvent.ResetPaiScalingFactor ->
                    viewModelScope.launch {
                        val currentProfile = settingsRepo.userPreferences.first().physiologyProfile
                        val defaultFactor = PaiCalculator.getDefaultPaiScalingFactor(currentProfile)
                        settingsRepo.updatePaiScalingFactor(defaultFactor)
                        healthSyncUseCase.sync()
                    }
                else -> {}
            }
        }
    }
