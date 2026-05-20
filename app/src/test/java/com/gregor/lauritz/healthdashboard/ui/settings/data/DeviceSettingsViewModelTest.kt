package com.gregor.lauritz.healthdashboard.ui.settings.data

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceSettingsViewModelTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var viewModel: DeviceSettingsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { settingsRepo.primaryDeviceName } returns MutableStateFlow("Test Device")
        coEvery { settingsRepo.getAvailableDevices() } returns listOf("Test Device", "Other Device")

        viewModel = DeviceSettingsViewModel(settingsRepo)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state loads primary device and available devices`() =
        runTest(testDispatcher) {
            val job =
                backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
                    viewModel.primaryDevice.collect { }
                }
            advanceUntilIdle()
            assertEquals("Test Device", viewModel.primaryDevice.value)
            assertEquals(listOf("Test Device", "Other Device"), viewModel.availableDevices.value)
            job.cancel()
        }

    @Test
    fun `updatePrimaryDevice calls repository`() =
        runTest(testDispatcher) {
            viewModel.updatePrimaryDevice("Other Device")
            coVerify { settingsRepo.updatePrimaryDevice("Other Device") }
        }

    @Test
    fun `refreshAvailableDevices clears cache and reloads`() =
        runTest(testDispatcher) {
            viewModel.refreshAvailableDevices()
            advanceUntilIdle()
            coVerify { settingsRepo.clearDeviceCache() }
            coVerify { settingsRepo.getAvailableDevices() }
        }
}
