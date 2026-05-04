package com.gregor.lauritz.healthdashboard.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.preferences.AppConfigRepository
import com.gregor.lauritz.healthdashboard.data.preferences.PhysiologyProfile
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val prefsRepo: UserPreferencesRepository,
    private val appConfigRepo: AppConfigRepository
) : ViewModel() {

    fun saveProfile(
        day: Int,
        month: Int,
        year: Int,
        gender: String?,
        physiologyProfile: PhysiologyProfile,
        dynamicColorEnabled: Boolean,
        onComplete: () -> Unit
    ) {
        viewModelScope.launch {
            prefsRepo.updateBirthday(day, month, year)
            prefsRepo.updateGender(gender)
            prefsRepo.updatePhysiologyProfile(physiologyProfile)
            appConfigRepo.updateDynamicColorEnabled(dynamicColorEnabled)
            onComplete()
        }
    }
}
