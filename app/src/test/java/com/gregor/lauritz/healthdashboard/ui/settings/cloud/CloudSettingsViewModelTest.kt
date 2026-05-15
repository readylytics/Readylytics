package com.gregor.lauritz.healthdashboard.ui.settings.cloud

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CloudSettingsViewModelTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var primaryDeviceFlow: MutableStateFlow<String?>

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        settingsRepo = mockk(relaxed = true)
        primaryDeviceFlow = MutableStateFlow(null)
        every { settingsRepo.primaryDeviceName } returns primaryDeviceFlow
        coEvery { settingsRepo.getAvailableDevices() } returns emptyList()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun init_loadsAvailableDevicesFromRepository() =
        runTest(testDispatcher) {
            val devices = listOf("Pixel 8", "Galaxy Watch")
            coEvery { settingsRepo.getAvailableDevices() } returns devices

            val viewModel = CloudSettingsViewModel(settingsRepo)
            advanceUntilIdle()

            assertEquals(devices, viewModel.availableDevices.value)
            coVerify(exactly = 1) { settingsRepo.getAvailableDevices() }
        }

    @Test
    fun init_emptyDeviceList_exposesEmptyList() =
        runTest(testDispatcher) {
            coEvery { settingsRepo.getAvailableDevices() } returns emptyList()

            val viewModel = CloudSettingsViewModel(settingsRepo)
            advanceUntilIdle()

            assertEquals(emptyList<String>(), viewModel.availableDevices.value)
        }

    @Test
    fun primaryDevice_initialValueIsNull() =
        runTest(testDispatcher) {
            val viewModel = CloudSettingsViewModel(settingsRepo)
            advanceUntilIdle()
            assertNull(viewModel.primaryDevice.value)
        }

    @Test
    fun primaryDevice_reflectsRepositoryFlowValue() =
        runTest(testDispatcher) {
            primaryDeviceFlow.value = "Pixel 8"
            val viewModel = CloudSettingsViewModel(settingsRepo)
            advanceUntilIdle()

            assertEquals("Pixel 8", viewModel.primaryDevice.first { it != null })
        }

    @Test
    fun primaryDevice_updatesWhenRepositoryFlowEmitsNewValue() =
        runTest(testDispatcher) {
            primaryDeviceFlow.value = "Pixel 8"
            val viewModel = CloudSettingsViewModel(settingsRepo)
            advanceUntilIdle()
            assertEquals("Pixel 8", viewModel.primaryDevice.first { it == "Pixel 8" })

            primaryDeviceFlow.value = "Galaxy Watch"
            advanceUntilIdle()
            assertEquals("Galaxy Watch", viewModel.primaryDevice.first { it == "Galaxy Watch" })
        }

    @Test
    fun updatePrimaryDevice_callsRepositoryWithDeviceName() =
        runTest(testDispatcher) {
            coEvery { settingsRepo.updatePrimaryDevice(any()) } returns Unit
            val viewModel = CloudSettingsViewModel(settingsRepo)
            advanceUntilIdle()

            viewModel.updatePrimaryDevice("Pixel 8")

            coVerify(exactly = 1) { settingsRepo.updatePrimaryDevice("Pixel 8") }
        }

    @Test
    fun updatePrimaryDevice_withNull_callsRepositoryWithNull() =
        runTest(testDispatcher) {
            coEvery { settingsRepo.updatePrimaryDevice(any()) } returns Unit
            val viewModel = CloudSettingsViewModel(settingsRepo)
            advanceUntilIdle()

            viewModel.updatePrimaryDevice(null)

            coVerify(exactly = 1) { settingsRepo.updatePrimaryDevice(null) }
        }

    @Test
    fun refreshAvailableDevices_reloadsListFromRepository() =
        runTest(testDispatcher) {
            coEvery { settingsRepo.getAvailableDevices() } returns listOf("Pixel 8")
            val viewModel = CloudSettingsViewModel(settingsRepo)
            advanceUntilIdle()
            assertEquals(listOf("Pixel 8"), viewModel.availableDevices.value)

            coEvery { settingsRepo.getAvailableDevices() } returns listOf("Pixel 8", "Galaxy Watch")
            viewModel.refreshAvailableDevices()
            advanceUntilIdle()

            assertEquals(listOf("Pixel 8", "Galaxy Watch"), viewModel.availableDevices.value)
            coVerify(exactly = 2) { settingsRepo.getAvailableDevices() }
        }
}
