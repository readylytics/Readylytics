package app.readylytics.health.ui.settings

import androidx.lifecycle.viewModelScope
import app.readylytics.health.core.ui.common.BaseViewModel
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.domain.model.Result
import app.readylytics.health.domain.model.getOrNull
import app.readylytics.health.domain.scoring.RasCalculator
import app.readylytics.health.domain.sync.HealthSyncUseCase
import app.readylytics.health.domain.user.UserUseCase
import app.readylytics.health.domain.validation.SettingsValidators
import app.readylytics.health.domain.validation.ValidationResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
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
                    val birthDate =
                        prefs.birthDate?.let {
                            try {
                                LocalDate.parse(it)
                            } catch (e: Exception) {
                                null
                            }
                        }
                    PhysiologySettingsState(
                        physiologyProfile = prefs.physiologyProfile,
                        age = prefs.age,
                        birthDate = birthDate,
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
                    val validation = SettingsValidators.BIRTHDAY_DATE_RULE.validate(event.date)
                    if (validation is ValidationResult.Valid) {
                        viewModelScope.launch {
                            userUseCase.updateBirthday(event.date).getOrNull()
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
                SettingsEvent.ResetRasScalingFactor ->
                    viewModelScope.launch {
                        val currentProfile = settingsRepo.userPreferences.first().physiologyProfile
                        val defaultFactor = RasCalculator.getDefaultRasScalingFactor(currentProfile)
                        settingsRepo.updateRasScalingFactor(defaultFactor)
                        healthSyncUseCase.sync()
                    }
                else -> {}
            }
        }
    }
