package com.gregor.lauritz.healthdashboard.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferencesRepository
import com.gregor.lauritz.healthdashboard.domain.user.UserUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PhysiologySettingsViewModel @Inject constructor(
    private val prefsRepo: UserPreferencesRepository,
    private val userUseCase: UserUseCase,
) : ViewModel() {

    val uiState: StateFlow<PhysiologySettingsState> = prefsRepo.userPreferences.map { prefs ->
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
        initialValue = PhysiologySettingsState()
    )

    fun onEvent(event: SettingsEvent) {
        when (event) {
            is SettingsEvent.BirthdayChanged -> {
                viewModelScope.launch {
                    userUseCase.updateBirthday(day = event.day, month = event.month, year = event.year)
                }
            }
            is SettingsEvent.GenderChanged -> {
                viewModelScope.launch { prefsRepo.updateGender(gender = event.gender) }
            }
            is SettingsEvent.PhysiologyProfileChanged ->
                viewModelScope.launch {
                    prefsRepo.updatePhysiologyProfile(profile = event.profile)
                }
            else -> {}
        }
    }
}
