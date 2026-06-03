package com.gregor.lauritz.healthdashboard.ui.settings

import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.domain.sync.HealthSyncUseCase
import com.gregor.lauritz.healthdashboard.domain.user.UserUseCase
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class PhysiologySettingsViewModelTest {
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var userUseCase: UserUseCase
    private lateinit var healthSyncUseCase: HealthSyncUseCase
    private lateinit var viewModel: PhysiologySettingsViewModel

    @Before
    fun setUp() {
        settingsRepo = mockk(relaxed = true)
        userUseCase = mockk(relaxed = true)
        healthSyncUseCase = mockk(relaxed = true)
        viewModel =
            PhysiologySettingsViewModel(
                settingsRepo = settingsRepo,
                userUseCase = userUseCase,
                healthSyncUseCase = healthSyncUseCase,
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
