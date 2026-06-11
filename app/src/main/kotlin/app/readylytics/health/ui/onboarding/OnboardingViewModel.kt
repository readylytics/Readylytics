package app.readylytics.health.ui.onboarding

import androidx.lifecycle.viewModelScope
import app.readylytics.health.data.preferences.PhysiologyProfile
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.preferences.UnitSystem
import app.readylytics.health.domain.model.Result
import app.readylytics.health.domain.service.BmiData
import app.readylytics.health.domain.service.BmiService
import app.readylytics.health.ui.common.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel
    @Inject
    constructor(
        private val settingsRepo: SettingsRepository,
        private val bmiService: BmiService,
    ) : BaseViewModel() {
        fun validateBirthdayDay(day: String): Result<Int> =
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

        fun validateBirthdayMonth(month: String): Result<Int> =
            try {
                val m = month.toInt()
                if (m in 1..12) {
                    Result.success(m)
                } else {
                    Result.failure("Month must be 1-12", "INVALID_MONTH")
                }
            } catch (e: NumberFormatException) {
                Result.failure("Month must be a number", "INVALID_FORMAT")
            }

        fun validateBirthdayYear(year: String): Result<Int> =
            try {
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

        fun validateHeight(height: String): Result<Float> =
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

        fun calculateBmi(
            weight: Float,
            height: Float,
        ): Result<BmiData> = bmiService.calculateBmi(weight, height, UnitSystem.METRIC)

        fun saveProfile(
            birthDate: LocalDate,
            gender: String?,
            physiologyProfile: PhysiologyProfile,
            dynamicColorEnabled: Boolean,
            unitSystem: UnitSystem,
            heightCm: Float?,
            onComplete: () -> Unit,
        ) {
            if (birthDate.isAfter(LocalDate.now())) {
                return
            }

            viewModelScope.launch {
                settingsRepo.updateBirthday(birthDate)
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
