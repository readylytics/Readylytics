package app.readylytics.health.ui.settings

import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.domain.preferences.DisplaySettings
import app.readylytics.health.domain.preferences.PhysiologySettings
import app.readylytics.health.domain.sync.HealthDataRefresh
import app.readylytics.health.domain.user.UserUseCase
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class PhysiologySettingsViewModelTest {
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var physiologySettings: PhysiologySettings
    private lateinit var displaySettings: DisplaySettings
    private lateinit var userUseCase: UserUseCase
    private lateinit var healthDataRefresh: HealthDataRefresh
    private lateinit var viewModel: PhysiologySettingsViewModel

    @Before
    fun setUp() {
        settingsRepo = mockk(relaxed = true)
        physiologySettings = mockk(relaxed = true)
        displaySettings = mockk(relaxed = true)
        userUseCase = mockk(relaxed = true)
        healthDataRefresh = mockk(relaxed = true)
        viewModel =
            PhysiologySettingsViewModel(
                settingsRepo = settingsRepo,
                physiologySettings = physiologySettings,
                displaySettings = displaySettings,
                userUseCase = userUseCase,
                healthDataRefresh = healthDataRefresh,
            )
    }

    @Test
    fun validateBirthdayDay_15_succeeds() {
        val result = viewModel.validateBirthdayDayForUpdate("15")
        assert(result.isSuccess)
    }

    @Test
    fun validateBirthdayDay_32_fails() {
        val result = viewModel.validateBirthdayDayForUpdate("32")
        assert(result.isFailure)
    }

    @Test
    fun validateHeight_175_succeeds() {
        val result = viewModel.validateHeightForUpdate("175.5")
        assert(result.isSuccess)
    }

    @Test
    fun validateHeight_tooShort_fails() {
        val result = viewModel.validateHeightForUpdate("50")
        assert(result.isFailure)
    }

    @Test
    fun onEvent_birthdayValid_updatesUserUseCase() {
        val birthDate = LocalDate.of(1990, 6, 15)
        viewModel.onEvent(SettingsEvent.BirthdayChanged(date = birthDate))
        coVerify(timeout = 1000) { userUseCase.updateBirthday(birthDate) }
    }

    @Test
    fun onEvent_birthdayInFuture_doesNotUpdateUserUseCase() {
        val futureDate = LocalDate.now().plusDays(1)
        viewModel.onEvent(SettingsEvent.BirthdayChanged(date = futureDate))
        coVerify(timeout = 100, inverse = true) { userUseCase.updateBirthday(any()) }
    }
}
