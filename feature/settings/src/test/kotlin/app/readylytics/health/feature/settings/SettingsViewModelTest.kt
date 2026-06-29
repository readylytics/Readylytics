package app.readylytics.health.feature.settings

import androidx.lifecycle.viewModelScope
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.domain.preferences.CircadianThresholdPreferences
import app.readylytics.health.domain.preferences.DeviceSettings
import app.readylytics.health.domain.preferences.DisplaySettings
import app.readylytics.health.domain.preferences.SyncSettings
import app.readylytics.health.domain.preferences.UserPreferencesReader
import app.readylytics.health.domain.sync.HealthDataRefresh
import app.readylytics.health.domain.sync.HistoricalResyncController
import app.readylytics.health.domain.sync.HistoricalResyncState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var settingsReader: UserPreferencesReader
    private lateinit var displaySettings: DisplaySettings
    private lateinit var syncSettings: SyncSettings
    private lateinit var deviceSettings: DeviceSettings
    private lateinit var healthDataRefresh: HealthDataRefresh
    private lateinit var circadianThresholdPreferences: CircadianThresholdPreferences

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        val preferences = MutableStateFlow(UserPreferences())
        settingsReader =
            mockk {
                every { userPreferences } returns preferences
            }
        displaySettings = mockk(relaxed = true)
        syncSettings = mockk(relaxed = true)
        deviceSettings =
            mockk(relaxed = true) {
                coEvery { getAvailableDevices() } returns emptyList()
            }
        healthDataRefresh = mockk(relaxed = true)
        circadianThresholdPreferences = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `UISettingsViewModel retention toggle event updates state`() =
        runTest {
            val viewModel =
                UISettingsViewModel(
                    settingsReader,
                    displaySettings,
                    healthDataRefresh,
                )
            viewModel.sharingStarted = SharingStarted.Eagerly
            viewModel.uiState

            viewModel.onEvent(SettingsEvent.RetentionDaysEnabledChanged(false))
            advanceUntilIdle()
            coVerify { displaySettings.updateRetentionDaysEnabled(false) }

            viewModel.onEvent(SettingsEvent.RetentionDaysEnabledChanged(true))
            advanceUntilIdle()
            coVerify { displaySettings.updateRetentionDaysEnabled(true) }
            viewModel.viewModelScope.cancel()
            advanceUntilIdle()
        }

    @Test
    fun `UISettingsViewModel retention days event updates state`() =
        runTest {
            val viewModel =
                UISettingsViewModel(
                    settingsReader,
                    displaySettings,
                    healthDataRefresh,
                )
            viewModel.sharingStarted = SharingStarted.Eagerly
            viewModel.uiState

            viewModel.onEvent(SettingsEvent.RetentionDaysChanged(500))
            advanceUntilIdle()
            coVerify { displaySettings.updateRetentionDays(500) }

            viewModel.onEvent(SettingsEvent.RetentionDaysChanged(180))
            advanceUntilIdle()
            coVerify { displaySettings.updateRetentionDays(180) }
            viewModel.viewModelScope.cancel()
            advanceUntilIdle()
        }

    @Test
    fun `UISettingsViewModel custom color events update state`() =
        runTest {
            val viewModel = UISettingsViewModel(settingsReader, displaySettings, healthDataRefresh)
            viewModel.sharingStarted = SharingStarted.Eagerly
            viewModel.uiState

            viewModel.onEvent(SettingsEvent.CustomPaletteEnabledChanged(true))
            advanceUntilIdle()
            coVerify { displaySettings.updateCustomPaletteEnabled(true) }

            viewModel.onEvent(SettingsEvent.CustomSecondaryColorChanged(0xFF112233L))
            advanceUntilIdle()
            coVerify { displaySettings.updateCustomSecondaryColor(0xFF112233L) }

            viewModel.onEvent(SettingsEvent.CustomTertiaryColorChanged(0xFF445566L))
            advanceUntilIdle()
            coVerify { displaySettings.updateCustomTertiaryColor(0xFF445566L) }

            viewModel.onEvent(SettingsEvent.CustomPrimaryColorChanged(0xFF556677L))
            advanceUntilIdle()
            coVerify { displaySettings.updateCustomPrimaryColor(0xFF556677L) }

            viewModel.viewModelScope.cancel()
            advanceUntilIdle()
        }

    @Test
    fun `SyncSettingsViewModel resync event enqueues worker`() =
        runTest {
            val mockRefresh = mockk<HealthDataRefresh>(relaxed = true)
            val mockHistoricalResyncController =
                mockk<HistoricalResyncController>(relaxed = true) {
                    every { state } returns flowOf(HistoricalResyncState(running = false, current = 0, total = 0))
                }

            val viewModel =
                SyncSettingsViewModel(
                    settingsReader,
                    syncSettings,
                    deviceSettings,
                    mockRefresh,
                    mockHistoricalResyncController,
                )
            viewModel.sharingStarted = SharingStarted.Lazily

            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.uiState.collect { }
            }

            assertFalse(viewModel.uiState.value.isResyncing)
            viewModel.onEvent(SettingsEvent.ResyncHealthConnect)
            advanceUntilIdle()

            coVerify { mockHistoricalResyncController.requestHistoricalResync() }
        }
}
