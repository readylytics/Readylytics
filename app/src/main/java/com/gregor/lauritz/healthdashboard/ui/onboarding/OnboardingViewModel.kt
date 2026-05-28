package com.gregor.lauritz.healthdashboard.ui.onboarding

import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.preferences.PhysiologyProfile
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.preferences.UnitSystem
import com.gregor.lauritz.healthdashboard.domain.model.Result
import com.gregor.lauritz.healthdashboard.domain.service.BmiService
import com.gregor.lauritz.healthdashboard.domain.service.BmiData
import com.gregor.lauritz.healthdashboard.domain.service.PreferenceService
import com.gregor.lauritz.healthdashboard.domain.service.ValidationService
import com.gregor.lauritz.healthdashboard.ui.common.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel
    @Inject
    constructor(
        private val settingsRepo: SettingsRepository,
        private val preferenceService: PreferenceService,
        private val bmiService: BmiService,
        private val validationService: ValidationService,
    ) : BaseViewModel() {
        fun validateBirthdayDay(day: String): Result<Int> {
            return try {
                val d = day.toInt()
                if (d in 1..31) {
                    Result.success(d)
                } else {
                    Result.failure("Day must be 1-31", "INVALID_DAY")
                }
            } catch (e: NumberFormatException) {
                Result.failure("Day must be a number", "INVALID_FORMAT")
            }
        }

        fun validateBirthdayMonth(month: String): Result<Int> {
            return try {
                val m = month.toInt()
                if (m in 1..12) {
                    Result.success(m)
                } else {
                    Result.failure("Month must be 1-12", "INVALID_MONTH")
                }
            } catch (e: NumberFormatException) {
                Result.failure("Month must be a number", "INVALID_FORMAT")
            }
        }

        fun validateBirthdayYear(year: String): Result<Int> {
            return try {
                val y = year.toInt()
                val now = LocalDate.now().year
                if (y in 1900..now) {
                    Result.success(y)
                } else {
                    Result.failure("Year must be 1900–$now", "INVALID_YEAR")
                }
            } catch (e: NumberFormatException) {
                Result.failure("Year must be a number", "INVALID_FORMAT")
            }
        }

        fun validateHeight(height: String): Result<Float> {
            return try {
                val h = height.toFloat()
                if (h in 120f..250f) {
                    Result.success(h)
                } else {
                    Result.failure("Height must be 120–250 cm", "INVALID_HEIGHT")
                }
            } catch (e: NumberFormatException) {
                Result.failure("Height must be a number", "INVALID_FORMAT")
            }
        }

        fun calculateBmi(weight: Float, height: Float): Result<BmiData> {
            return bmiService.calculateBmi(weight, height, UnitSystem.METRIC)
        }

        fun saveProfile(
            day: Int,
            month: Int,
            year: Int,
            gender: String?,
            physiologyProfile: PhysiologyProfile,
            dynamicColorEnabled: Boolean,
            unitSystem: UnitSystem,
            heightCm: Float?,
            onComplete: () -> Unit,
        ) {
            val dayValidation = validateBirthdayDay(day.toString())
            val monthValidation = validateBirthdayMonth(month.toString())
            val yearValidation = validateBirthdayYear(year.toString())

            if (dayValidation.isFailure || monthValidation.isFailure || yearValidation.isFailure) {
                return
            }

            viewModelScope.launch {
                settingsRepo.updateBirthday(day, month, year)
                settingsRepo.updateGender(gender)
                settingsRepo.updatePhysiologyProfile(physiologyProfile)
                settingsRepo.updateDynamicColorEnabled(dynamicColorEnabled)
                settingsRepo.updateUnitSystem(unitSystem)
                settingsRepo.updateHeight(heightCm)
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
