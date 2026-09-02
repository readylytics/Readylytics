package app.readylytics.health.feature.settings

import androidx.lifecycle.viewModelScope
import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.preferences.CircadianThresholdPreferences
import app.readylytics.health.core.model.domain.preferences.DeviceSettings
import app.readylytics.health.core.model.domain.preferences.DisplaySettings
import app.readylytics.health.core.model.domain.preferences.SyncSettings
import app.readylytics.health.core.model.domain.preferences.UserPreferencesReader
import app.readylytics.health.core.model.domain.sync.HealthDataRefresh
import app.readylytics.health.core.model.domain.sync.HistoricalResyncController
import app.readylytics.health.core.model.domain.sync.HistoricalResyncState
import app.readylytics.health.core.model.domain.workouts.WorkoutDetailLayoutRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.roundToInt

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
                    workoutDetailLayoutRepository = mockk<WorkoutDetailLayoutRepository>(relaxed = true),
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
                    workoutDetailLayoutRepository = mockk<WorkoutDetailLayoutRepository>(relaxed = true),
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
            val viewModel =
                UISettingsViewModel(
                    settingsReader,
                    displaySettings,
                    healthDataRefresh,
                    workoutDetailLayoutRepository = mockk<WorkoutDetailLayoutRepository>(relaxed = true),
                )
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
    fun `hrrTolerancePreference maps to ui state`() =
        runTest {
            val preferences = MutableStateFlow(UserPreferences(hrrToleranceSeconds = 45))
            settingsReader =
                mockk {
                    every { userPreferences } returns preferences
                }
            val viewModel =
                UISettingsViewModel(
                    settingsReader,
                    displaySettings,
                    healthDataRefresh,
                    workoutDetailLayoutRepository = mockk<WorkoutDetailLayoutRepository>(relaxed = true),
                )
            viewModel.sharingStarted = SharingStarted.Eagerly

            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.uiState.collect { }
            }
            advanceUntilIdle()

            assertEquals(45, viewModel.uiState.value.hrrToleranceSeconds)

            viewModel.viewModelScope.cancel()
            advanceUntilIdle()
        }

    @Test
    fun `HrrToleranceSecondsChanged persists without refreshing health data`() =
        runTest {
            val viewModel =
                UISettingsViewModel(
                    settingsReader,
                    displaySettings,
                    healthDataRefresh,
                    workoutDetailLayoutRepository = mockk<WorkoutDetailLayoutRepository>(relaxed = true),
                )
            viewModel.sharingStarted = SharingStarted.Eagerly
            viewModel.uiState

            viewModel.onEvent(SettingsEvent.HrrToleranceSecondsChanged(45))
            advanceUntilIdle()

            coVerify { displaySettings.updateHrrToleranceSeconds(45) }
            coVerify(exactly = 0) { healthDataRefresh.refreshAffectedWindow() }

            viewModel.viewModelScope.cancel()
            advanceUntilIdle()
        }

    @Test
    fun `TrimpModelChanged triggers a historical recompute, not a recent-window refresh`() =
        runTest {
            // SCORE-007: the TRIMP model changes how every persisted historical day's TRIMP was
            // computed, so it must escalate to the full recompute path, not the 8-day window.
            val viewModel =
                UISettingsViewModel(
                    settingsReader,
                    displaySettings,
                    healthDataRefresh,
                    workoutDetailLayoutRepository = mockk<WorkoutDetailLayoutRepository>(relaxed = true),
                )
            viewModel.sharingStarted = SharingStarted.Eagerly
            viewModel.uiState

            viewModel.onEvent(
                SettingsEvent.TrimpModelChanged(app.readylytics.health.core.model.domain.scoring.TrimpModel.I_TRIMP),
            )
            advanceUntilIdle()

            coVerify {
                displaySettings.updateTrimpModel(
                    app.readylytics.health.core.model.domain.scoring.TrimpModel.I_TRIMP,
                )
            }
            coVerify(exactly = 1) { healthDataRefresh.refreshHistorical() }
            coVerify(exactly = 0) { healthDataRefresh.refreshAffectedWindow() }

            viewModel.viewModelScope.cancel()
            advanceUntilIdle()
        }

    @Test
    fun `StepGoalChanged still uses the recent-window refresh, not a historical recompute`() =
        runTest {
            // Confirms the split didn't over-broaden: a display-only goal isn't a scoring input.
            val viewModel =
                UISettingsViewModel(
                    settingsReader,
                    displaySettings,
                    healthDataRefresh,
                    workoutDetailLayoutRepository = mockk<WorkoutDetailLayoutRepository>(relaxed = true),
                )
            viewModel.sharingStarted = SharingStarted.Eagerly
            viewModel.uiState

            viewModel.onEvent(SettingsEvent.StepGoalChanged(8_000))
            advanceUntilIdle()

            coVerify(exactly = 1) { healthDataRefresh.refreshAffectedWindow() }
            coVerify(exactly = 0) { healthDataRefresh.refreshHistorical() }

            viewModel.viewModelScope.cancel()
            advanceUntilIdle()
        }

    @Test
    fun `HrrToleranceSecondsChanged rejects invalid values`() =
        runTest {
            val viewModel =
                UISettingsViewModel(
                    settingsReader,
                    displaySettings,
                    healthDataRefresh,
                    workoutDetailLayoutRepository = mockk<WorkoutDetailLayoutRepository>(relaxed = true),
                )
            viewModel.sharingStarted = SharingStarted.Eagerly
            viewModel.uiState

            viewModel.onEvent(SettingsEvent.HrrToleranceSecondsChanged(61))
            advanceUntilIdle()

            coVerify(exactly = 0) { displaySettings.updateHrrToleranceSeconds(any()) }

            viewModel.viewModelScope.cancel()
            advanceUntilIdle()
        }

    @Test
    fun `residual fatigue half life persists and triggers historical recompute, rejects invalid`() =
        runTest {
            val viewModel =
                UISettingsViewModel(
                    settingsReader,
                    displaySettings,
                    healthDataRefresh,
                    workoutDetailLayoutRepository = mockk<WorkoutDetailLayoutRepository>(relaxed = true),
                )
            viewModel.sharingStarted = SharingStarted.Eagerly
            viewModel.uiState

            viewModel.onEvent(SettingsEvent.ResidualFatigueHalfLifeChanged(48f))
            advanceUntilIdle()
            coVerifyOrder {
                displaySettings.updateResidualFatigueHalfLifeHours(48f)
                healthDataRefresh.refreshHistorical()
            }
            coVerify(exactly = 1) { healthDataRefresh.refreshHistorical() }

            viewModel.onEvent(SettingsEvent.ResidualFatigueHalfLifeChanged(5f))
            advanceUntilIdle()
            coVerify(exactly = 0) { displaySettings.updateResidualFatigueHalfLifeHours(5f) }
            coVerify(exactly = 1) { healthDataRefresh.refreshHistorical() }
            coVerify(exactly = 0) { healthDataRefresh.refreshAffectedWindow() }

            viewModel.viewModelScope.cancel()
            advanceUntilIdle()
        }

    @Test
    fun `residual fatigue gain persists and triggers historical recompute, rejects invalid`() =
        runTest {
            val viewModel =
                UISettingsViewModel(
                    settingsReader,
                    displaySettings,
                    healthDataRefresh,
                    workoutDetailLayoutRepository = mockk<WorkoutDetailLayoutRepository>(relaxed = true),
                )
            viewModel.sharingStarted = SharingStarted.Eagerly
            viewModel.uiState

            viewModel.onEvent(SettingsEvent.ResidualFatigueGainChanged(2.5f))
            advanceUntilIdle()
            coVerifyOrder {
                displaySettings.updateResidualFatigueGain(2.5f)
                healthDataRefresh.refreshHistorical()
            }
            coVerify(exactly = 1) { healthDataRefresh.refreshHistorical() }

            viewModel.onEvent(SettingsEvent.ResidualFatigueGainChanged(10f))
            advanceUntilIdle()
            coVerify(exactly = 0) { displaySettings.updateResidualFatigueGain(10f) }
            coVerify(exactly = 1) { healthDataRefresh.refreshHistorical() }
            coVerify(exactly = 0) { healthDataRefresh.refreshAffectedWindow() }

            viewModel.viewModelScope.cancel()
            advanceUntilIdle()
        }

    @Test
    fun `residual fatigue slider stops land on whole-hour and 0_1 increments`() {
        // M3 Slider.steps counts the stops *between* the endpoints, so the interval is
        // (max - min) / (steps + 1). A future range change that silently moves the documented
        // defaults off-grid fails here rather than shipping a slider that renders "23.9 h".
        val halfLifeInterval =
            (
                SettingsDefaults.MAX_RESIDUAL_FATIGUE_HALF_LIFE_HOURS -
                    SettingsDefaults.MIN_RESIDUAL_FATIGUE_HALF_LIFE_HOURS
            ) / (RESIDUAL_FATIGUE_HALF_LIFE_SLIDER_STEPS + 1)
        assertEquals(1.0f, halfLifeInterval, 1e-4f)
        assertOnStop(
            value = SettingsDefaults.RESIDUAL_FATIGUE_HALF_LIFE_HOURS,
            min = SettingsDefaults.MIN_RESIDUAL_FATIGUE_HALF_LIFE_HOURS,
            interval = halfLifeInterval,
        )

        val gainInterval =
            (
                SettingsDefaults.MAX_RESIDUAL_FATIGUE_GAIN -
                    SettingsDefaults.MIN_RESIDUAL_FATIGUE_GAIN
            ) / (RESIDUAL_FATIGUE_GAIN_SLIDER_STEPS + 1)
        assertEquals(0.1f, gainInterval, 1e-4f)
        assertOnStop(
            value = SettingsDefaults.RESIDUAL_FATIGUE_GAIN,
            min = SettingsDefaults.MIN_RESIDUAL_FATIGUE_GAIN,
            interval = gainInterval,
        )
    }

    private fun assertOnStop(
        value: Float,
        min: Float,
        interval: Float,
    ) {
        val stopIndex = (value - min) / interval
        assertEquals(stopIndex.roundToInt().toFloat(), stopIndex, 1e-3f)
    }

    @Test
    fun `ResetFatigueToDefaults event resets fatigue settings to defaults and triggers historical recompute`() =
        runTest {
            val viewModel =
                UISettingsViewModel(
                    settingsReader,
                    displaySettings,
                    healthDataRefresh,
                    workoutDetailLayoutRepository = mockk<WorkoutDetailLayoutRepository>(relaxed = true),
                )
            viewModel.sharingStarted = SharingStarted.Eagerly
            viewModel.uiState

            viewModel.onEvent(SettingsEvent.ResetFatigueToDefaults)
            advanceUntilIdle()

            coVerifyOrder {
                displaySettings.resetResidualFatigueToDefaults()
                healthDataRefresh.refreshHistorical()
            }
            coVerify(exactly = 1) { healthDataRefresh.refreshHistorical() }
            coVerify(exactly = 0) { healthDataRefresh.refreshAffectedWindow() }

            viewModel.viewModelScope.cancel()
            advanceUntilIdle()
        }

    @Test
    fun `SyncSettingsViewModel resync event enqueues worker and isResyncing follows durable state`() =
        runTest {
            val mockRefresh = mockk<HealthDataRefresh>(relaxed = true)
            val resyncStateFlow =
                MutableStateFlow(
                    HistoricalResyncState(running = false, current = 0, total = 0),
                )
            val mockHistoricalResyncController =
                mockk<HistoricalResyncController>(relaxed = true) {
                    every { state } returns resyncStateFlow
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
            resyncStateFlow.value = HistoricalResyncState(running = true, current = 5, total = 10)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isResyncing)
            assertEquals(5, viewModel.uiState.value.resyncCurrent)

            viewModel.onEvent(SettingsEvent.ResyncHealthConnect)
            advanceUntilIdle()

            coVerify { mockHistoricalResyncController.requestHistoricalResync() }
        }
}
