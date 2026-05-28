package com.gregor.lauritz.healthdashboard.ui.settings

import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.domain.model.Result
import com.gregor.lauritz.healthdashboard.domain.scoring.PaiCalculator
import com.gregor.lauritz.healthdashboard.domain.sync.HealthSyncUseCase
import com.gregor.lauritz.healthdashboard.domain.user.UserUseCase
import com.gregor.lauritz.healthdashboard.domain.validation.SettingsValidators
import com.gregor.lauritz.healthdashboard.domain.validation.ValidationResult
import com.gregor.lauritz.healthdashboard.ui.common.BaseViewModel
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
    ) : BaseViewModel() {
        fun validateBirthdayDayForUpdate(day: String): Result<Int> =
            try {
                val d = day.toInt()
                if (d in 1..31) {
                    Result.success(d)
                } else {
                    Result.failure("Day must be 1-31", "INVALID_DAY")
                }
            } catch (e: NumberFormatException) {
                Result.failure("Day must be a number", "INVALID_FORMAT")
            }

        fun validateHeightForUpdate(height: String): Result<Float> =
            try {
                val h = height.toFloat()
                if (h in 120f..250f) {
                    Result.success(h)
                } else {
                    Result.failure("Height must be 120–250 cm", "INVALID_HEIGHT")
                }
            } catch (e: NumberFormatException) {
                Result.failure("Height must be a number", "INVALID_FORMAT")
            }

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
                        heightCm = prefs.heightCm,
                        unitSystem = prefs.unitSystem,
                    )
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5000),
                    initialValue = PhysiologySettingsState(),
                )

        fun onEvent(event: SettingsEvent) {
            when (event) {
                is SettingsEvent.BirthdayChanged -> {
                    val dayValidation = SettingsValidators.BIRTHDAY_DAY_RULE.validate(event.day.toString())
                    val monthValidation = SettingsValidators.BIRTHDAY_MONTH_RULE.validate(event.month.toString())
                    val yearValidation = SettingsValidators.BIRTHDAY_YEAR_RULE.validate(event.year.toString())

                    if (dayValidation is ValidationResult.Valid &&
                        monthValidation is ValidationResult.Valid &&
                        yearValidation is ValidationResult.Valid
                    ) {
                        viewModelScope.launch {
                            userUseCase.updateBirthday(day = event.day, month = event.month, year = event.year)
                            healthSyncUseCase.sync()
                        }
                    }
                }
                is SettingsEvent.GenderChanged -> {
                    viewModelScope.launch {
                        settingsRepo.updateGender(gender = event.gender?.displayName)
                        healthSyncUseCase.sync()
                    }
                }
                is SettingsEvent.HeightChanged -> {
                    viewModelScope.launch {
                        settingsRepo.updateHeight(heightCm = event.heightCm)
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
