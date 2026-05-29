package com.gregor.lauritz.healthdashboard.ui.onboarding

import com.gregor.lauritz.healthdashboard.data.preferences.PhysiologyProfile
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.preferences.UnitSystem
import com.gregor.lauritz.healthdashboard.domain.model.getOrNull
import com.gregor.lauritz.healthdashboard.domain.service.BmiService
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Before
import org.junit.Test

class OnboardingViewModelTest {
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var viewModel: OnboardingViewModel

    @Before
    fun setUp() {
        settingsRepo = mockk(relaxed = true)
        viewModel =
            OnboardingViewModel(
                settingsRepo = settingsRepo,
                bmiService = BmiService(),
            )
    }

    // ─── validateBirthdayDay ───────────────────────────────────────────────
    @Test
    fun validateBirthdayDay_15_succeeds() {
        val result = viewModel.validateBirthdayDay("15")
        assert(result.isSuccess) { "Day 15 should be valid" }
    }

    @Test
    fun validateBirthdayDay_1_succeeds() {
        val result = viewModel.validateBirthdayDay("1")
        assert(result.isSuccess) { "Day 1 should be valid" }
    }

    @Test
    fun validateBirthdayDay_31_succeeds() {
        val result = viewModel.validateBirthdayDay("31")
        assert(result.isSuccess) { "Day 31 should be valid" }
    }

    @Test
    fun validateBirthdayDay_0_fails() {
        val result = viewModel.validateBirthdayDay("0")
        assert(result.isFailure) { "Day 0 should be invalid" }
    }

    @Test
    fun validateBirthdayDay_32_fails() {
        val result = viewModel.validateBirthdayDay("32")
        assert(result.isFailure) { "Day 32 should be invalid" }
    }

    @Test
    fun validateBirthdayDay_negative_fails() {
        val result = viewModel.validateBirthdayDay("-1")
        assert(result.isFailure) { "Negative day should be invalid" }
    }

    // ─── validateBirthdayMonth ─────────────────────────────────────────────
    @Test
    fun validateBirthdayMonth_6_succeeds() {
        val result = viewModel.validateBirthdayMonth("6")
        assert(result.isSuccess) { "Month 6 should be valid" }
    }

    @Test
    fun validateBirthdayMonth_1_succeeds() {
        val result = viewModel.validateBirthdayMonth("1")
        assert(result.isSuccess) { "Month 1 should be valid" }
    }

    @Test
    fun validateBirthdayMonth_12_succeeds() {
        val result = viewModel.validateBirthdayMonth("12")
        assert(result.isSuccess) { "Month 12 should be valid" }
    }

    @Test
    fun validateBirthdayMonth_0_fails() {
        val result = viewModel.validateBirthdayMonth("0")
        assert(result.isFailure) { "Month 0 should be invalid" }
    }

    @Test
    fun validateBirthdayMonth_13_fails() {
        val result = viewModel.validateBirthdayMonth("13")
        assert(result.isFailure) { "Month 13 should be invalid" }
    }

    // ─── validateBirthdayYear ──────────────────────────────────────────────
    @Test
    fun validateBirthdayYear_1990_succeeds() {
        val result = viewModel.validateBirthdayYear("1990")
        assert(result.isSuccess) { "Year 1990 should be valid" }
    }

    @Test
    fun validateBirthdayYear_futureYear_fails() {
        val result = viewModel.validateBirthdayYear("2099")
        assert(result.isFailure) { "Future year should be invalid" }
    }

    @Test
    fun validateBirthdayYear_tooOldYear_fails() {
        val result = viewModel.validateBirthdayYear("1800")
        assert(result.isFailure) { "Too old year should be invalid" }
    }

    // ─── validateHeight ────────────────────────────────────────────────────
    @Test
    fun validateHeight_175_succeeds() {
        val result = viewModel.validateHeight("175.5")
        assert(result.isSuccess) { "Height 175 should be valid" }
    }

    @Test
    fun validateHeight_120_succeeds() {
        val result = viewModel.validateHeight("120")
        assert(result.isSuccess) { "Minimum height 120 should be valid" }
    }

    @Test
    fun validateHeight_tooShort_fails() {
        val result = viewModel.validateHeight("50")
        assert(result.isFailure) { "Height 50 should be invalid" }
    }

    @Test
    fun validateHeight_tooTall_fails() {
        val result = viewModel.validateHeight("300")
        assert(result.isFailure) { "Height 300 should be invalid" }
    }

    // ─── calculateBmi ──────────────────────────────────────────────────────
    @Test
    fun calculateBmi_70kg_175cm_succeeds() {
        val result = viewModel.calculateBmi(weight = 70f, height = 175f)
        assert(result.isSuccess) { "BMI calculation should succeed" }
        assert((result.getOrNull()?.bmi ?: -1f) > 0) { "BMI should be positive" }
    }

    @Test
    fun calculateBmi_zeroWeight_fails() {
        val result = viewModel.calculateBmi(weight = 0f, height = 175f)
        assert(result.isFailure) { "Zero weight should fail" }
    }

    @Test
    fun calculateBmi_zeroHeight_fails() {
        val result = viewModel.calculateBmi(weight = 70f, height = 0f)
        assert(result.isFailure) { "Zero height should fail" }
    }

    // ─── saveProfile ───────────────────────────────────────────────────────
    @Test
    fun saveProfile_validInput_callsRepository() {
        var completed = false
        viewModel.saveProfile(
            day = 15,
            month = 6,
            year = 1990,
            gender = "Male",
            physiologyProfile = PhysiologyProfile.ATHLETE,
            dynamicColorEnabled = true,
            unitSystem = UnitSystem.METRIC,
            heightCm = 175f,
            onComplete = { completed = true },
        )
        coVerify(timeout = 1000) { settingsRepo.updateBirthday(15, 6, 1990) }
        assert(completed) { "onComplete should be called" }
    }

    @Test
    fun saveProfile_invalidDay_doesNotCallRepository() {
        var completed = false
        viewModel.saveProfile(
            day = 32,
            month = 6,
            year = 1990,
            gender = "Male",
            physiologyProfile = PhysiologyProfile.ATHLETE,
            dynamicColorEnabled = true,
            unitSystem = UnitSystem.METRIC,
            heightCm = 175f,
            onComplete = { completed = true },
        )
        coVerify(timeout = 100, inverse = true) { settingsRepo.updateBirthday(any(), any(), any()) }
        assert(!completed) { "onComplete should not be called with invalid input" }
    }
}
