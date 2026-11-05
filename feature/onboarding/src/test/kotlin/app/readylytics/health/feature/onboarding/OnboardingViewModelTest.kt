package app.readylytics.health.feature.onboarding

import app.readylytics.health.core.model.data.preferences.PhysiologyProfile
import app.readylytics.health.core.model.data.preferences.UnitSystem
import app.readylytics.health.core.model.domain.model.Result
import app.readylytics.health.core.model.domain.model.getOrNull
import app.readylytics.health.core.model.domain.preferences.DisplaySettings
import app.readylytics.health.core.model.domain.preferences.PhysiologySettings
import app.readylytics.health.core.model.domain.service.BmiService
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private val clock = Clock.fixed(Instant.parse("2026-06-15T10:00:00Z"), ZoneOffset.UTC)
    private lateinit var physiologySettings: PhysiologySettings
    private lateinit var displaySettings: DisplaySettings
    private lateinit var viewModel: OnboardingViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        physiologySettings = mockk(relaxed = true)
        displaySettings = mockk(relaxed = true)
        viewModel =
            OnboardingViewModel(
                physiologySettings = physiologySettings,
                displaySettings = displaySettings,
                bmiService = BmiService(),
                clock = clock,
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
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

    @Test
    fun validateBirthdayYear_clockYear_succeeds() {
        assert(viewModel.validateBirthdayYear("2026").isSuccess) { "Current clock year should be valid" }
    }

    @Test
    fun validateBirthdayYear_yearAfterClockYear_failsWithClockYearInMessage() {
        val result = viewModel.validateBirthdayYear("2027")
        assert(result is Result.Failure && result.reason == "Year must be 1900–2026") { "got $result" }
    }

    @Test
    fun validateBirthdayYear_1900_succeedsAnd1899_fails() {
        assert(viewModel.validateBirthdayYear("1900").isSuccess) { "1900 is the lower bound" }
        assert(viewModel.validateBirthdayYear("1899").isFailure) { "1899 is below the lower bound" }
    }

    @Test
    fun validateBirthdayYear_usesClockZoneAtYearBoundary() {
        // 2026-12-31T23:30Z is already 2027-01-01 at UTC+14: the clock's zone decides the year.
        val zonedClock = Clock.fixed(Instant.parse("2026-12-31T23:30:00Z"), ZoneOffset.ofHours(14))
        val zonedViewModel =
            OnboardingViewModel(
                physiologySettings = physiologySettings,
                displaySettings = displaySettings,
                bmiService = BmiService(),
                clock = zonedClock,
            )
        assert(zonedViewModel.validateBirthdayYear("2027").isSuccess) { "2027 is the clock-zone year" }
        assert(zonedViewModel.validateBirthdayYear("2028").isFailure) { "2028 is after the clock-zone year" }
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
        val birthDate = LocalDate.of(1990, 6, 15)
        viewModel.saveProfile(
            birthDate = birthDate,
            gender = "Male",
            physiologyProfile = PhysiologyProfile.ATHLETE,
            dynamicColorEnabled = true,
            unitSystem = UnitSystem.METRIC,
            heightCm = 175f,
            onComplete = { completed = true },
        )
        coVerify(timeout = 1000) { physiologySettings.updateBirthday(birthDate) }
        assert(completed) { "onComplete should be called" }
    }

    @Test
    fun saveProfile_birthDateOnClockToday_callsRepository() {
        val today = LocalDate.now(clock)
        viewModel.saveProfile(
            birthDate = today,
            gender = "Male",
            physiologyProfile = PhysiologyProfile.ATHLETE,
            dynamicColorEnabled = true,
            unitSystem = UnitSystem.METRIC,
            heightCm = 175f,
            onComplete = {},
        )
        coVerify(timeout = 1000) { physiologySettings.updateBirthday(today) }
    }

    @Test
    fun saveProfile_futureDate_doesNotCallRepository() {
        var completed = false
        val futureDate = LocalDate.now(clock).plusDays(1)
        viewModel.saveProfile(
            birthDate = futureDate,
            gender = "Male",
            physiologyProfile = PhysiologyProfile.ATHLETE,
            dynamicColorEnabled = true,
            unitSystem = UnitSystem.METRIC,
            heightCm = 175f,
            onComplete = { completed = true },
        )
        coVerify(timeout = 100, inverse = true) { physiologySettings.updateBirthday(any()) }
        assert(!completed) { "onComplete should not be called with future date" }
    }

    @Test
    fun saveProfile_preNineteenHundredDate_doesNotCallRepository() {
        var completed = false
        viewModel.saveProfile(
            birthDate = LocalDate.of(1899, 12, 31),
            gender = "Male",
            physiologyProfile = PhysiologyProfile.ATHLETE,
            dynamicColorEnabled = true,
            unitSystem = UnitSystem.METRIC,
            heightCm = 175f,
            onComplete = { completed = true },
        )
        coVerify(timeout = 100, inverse = true) { physiologySettings.updateBirthday(any()) }
        assert(!completed) { "onComplete should not be called with a pre-1900 date" }
    }

    @Test
    fun birthdayBounds_reflectsInjectedClock() {
        val bounds = viewModel.birthdayBounds()
        assert(bounds.yearRange == 1900..2026) { "year range should end at the clock year" }
        assert(bounds.maxDate == LocalDate.of(2026, 6, 15)) { "maxDate should be the clock date" }
    }

    // ─── saveRetention ───────────────────────────────────────────────────────
    @Test
    fun saveRetention_validRetention_callsRepository() {
        var completed = false
        val retentionDays = 360
        viewModel.saveRetention(retentionDays) { completed = true }
        coVerify(timeout = 1000) { displaySettings.updateRetentionDaysEnabled(true) }
        coVerify(timeout = 1000) { displaySettings.updateRetentionDays(retentionDays) }
        assert(completed) { "onComplete should be called" }
    }

    @Test
    fun saveRetention_invalidRetention_doesNotCallRepository() {
        var completed = false
        val invalidRetentionDays = 5 // Valid range is 90-1800 days (3-60 months)
        viewModel.saveRetention(invalidRetentionDays) { completed = true }
        coVerify(timeout = 100, inverse = true) { displaySettings.updateRetentionDaysEnabled(any()) }
        coVerify(timeout = 100, inverse = true) { displaySettings.updateRetentionDays(any()) }
        assert(!completed) { "onComplete should not be called with invalid retention" }
    }
}
