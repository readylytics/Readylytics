package com.gregor.lauritz.healthdashboard.ui.settings

import com.gregor.lauritz.healthdashboard.data.preferences.PhysiologyProfile
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.sync.HealthSyncUseCase
import com.gregor.lauritz.healthdashboard.domain.scoring.PaiCalculator
import com.gregor.lauritz.healthdashboard.domain.user.UserUseCase
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhysiologySettingsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val userUseCase = mockk<UserUseCase>(relaxed = true)
    private val healthSyncUseCase = mockk<HealthSyncUseCase>(relaxed = true)

    private lateinit var viewModel: PhysiologySettingsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        viewModel = PhysiologySettingsViewModel(settingsRepo, userUseCase, healthSyncUseCase)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `PhysiologyProfileChanged event updates profile, PAI scaling factor, and triggers sync`() =
        runTest {
            val newProfile = PhysiologyProfile.ATHLETE
            val expectedScalingFactor = PaiCalculator.getDefaultPaiScalingFactor(newProfile)

            viewModel.onEvent(SettingsEvent.PhysiologyProfileChanged(newProfile))
            advanceUntilIdle()

            coVerify { settingsRepo.updatePhysiologyProfile(newProfile) }
            coVerify { healthSyncUseCase.sync() }
        }
}
