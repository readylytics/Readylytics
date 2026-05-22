package com.gregor.lauritz.healthdashboard.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.preferences.PhysiologyProfile
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.preferences.UnitSystem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel
    @Inject
    constructor(
        private val settingsRepo: SettingsRepository,
    ) : ViewModel() {
        fun saveProfile(
            day: Int,
            month: Int,
            year: Int,
            gender: String?,
            physiologyProfile: PhysiologyProfile,
            dynamicColorEnabled: Boolean,
            unitSystem: UnitSystem,
            onComplete: () -> Unit,
        ) {
            viewModelScope.launch {
                settingsRepo.updateBirthday(day, month, year)
                settingsRepo.updateGender(gender)
                settingsRepo.updatePhysiologyProfile(physiologyProfile)
                settingsRepo.updateDynamicColorEnabled(dynamicColorEnabled)
                settingsRepo.updateUnitSystem(unitSystem)
                onComplete()
            }
        }

        fun selectDevice(
            deviceName: String?,
            onComplete: () -> Unit,
        ) {
            viewModelScope.launch {
                settingsRepo.updatePrimaryDevice(deviceName)
                onComplete()
            }
        }
    }
