package app.readylytics.health.ui.settings

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.readylytics.health.data.preferences.CircadianThresholdPreferences
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.domain.sync.HealthDataRefresh
import app.readylytics.health.domain.sync.HistoricalResyncController
import app.readylytics.health.domain.sync.HistoricalResyncState
import app.readylytics.health.domain.repository.ScoringRepository
import app.readylytics.health.workers.WorkerScheduler
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class)
class SettingsViewModelTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var context: Context

    @Inject
    lateinit var settingsRepo: SettingsRepository

    @Inject
    lateinit var scoringRepository: ScoringRepository

    @Inject
    lateinit var healthDataRefresh: HealthDataRefresh

    @Inject
    lateinit var workerScheduler: WorkerScheduler

    @Inject
    lateinit var circadianThresholdPreferences: CircadianThresholdPreferences

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = InstrumentationRegistry.getInstrumentation().targetContext

        // Initialize WorkManager for tests
        try {
            val config =
                androidx.work.Configuration
                    .Builder()
                    .setMinimumLoggingLevel(android.util.Log.DEBUG)
                    .build()
            androidx.work.WorkManager.initialize(context, config)
        } catch (e: IllegalStateException) {
            // Already initialized
        }

        hiltRule.inject()
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
                    settingsRepo,
                    settingsRepo,
                    healthDataRefresh,
                )
            viewModel.sharingStarted = SharingStarted.Lazily

            // Launch collection in backgroundScope to handle Lazily start and automatic cleanup
            val job =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    viewModel.uiState.collect()
                }

            viewModel.onEvent(SettingsEvent.RetentionDaysEnabledChanged(false))
            advanceUntilIdle()

            val state = viewModel.uiState.first { !it.retentionDaysEnabled }
            assertFalse(state.retentionDaysEnabled)

            viewModel.onEvent(SettingsEvent.RetentionDaysEnabledChanged(true))
            advanceUntilIdle()

            val state2 = viewModel.uiState.first { it.retentionDaysEnabled }
            assertTrue(state2.retentionDaysEnabled)
            viewModel.viewModelScope.cancel()
        }

    @Test
    fun `UISettingsViewModel retention days event updates state`() =
        runTest {
            val viewModel =
                UISettingsViewModel(
                    settingsRepo,
                    settingsRepo,
                    healthDataRefresh,
                )
            viewModel.sharingStarted = SharingStarted.Lazily

            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.uiState.collect()
            }

            viewModel.onEvent(SettingsEvent.RetentionDaysChanged(500))
            advanceUntilIdle()

            val state = viewModel.uiState.first { it.retentionDays == 500 }
            assertEquals(500, state.retentionDays)

            viewModel.onEvent(SettingsEvent.RetentionDaysChanged(180))
            advanceUntilIdle()

            val state2 = viewModel.uiState.first { it.retentionDays == 180 }
            assertEquals(180, state2.retentionDays)
            viewModel.viewModelScope.cancel()
        }

    @Test
    fun `UISettingsViewModel custom color events update state`() =
        runTest {
            val viewModel = UISettingsViewModel(settingsRepo, settingsRepo, healthDataRefresh)
            viewModel.sharingStarted = SharingStarted.Lazily

            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.uiState.collect()
            }

            viewModel.onEvent(SettingsEvent.CustomPaletteEnabledChanged(true))
            advanceUntilIdle()
            val state1 = viewModel.uiState.first { it.isCustomPaletteEnabled }
            assertTrue(state1.isCustomPaletteEnabled)

            viewModel.onEvent(SettingsEvent.CustomSecondaryColorChanged(0xFF112233L))
            advanceUntilIdle()
            val state2 = viewModel.uiState.first { it.customSecondaryColor == 0xFF112233L }
            assertEquals(0xFF112233L, state2.customSecondaryColor)

            viewModel.onEvent(SettingsEvent.CustomTertiaryColorChanged(0xFF445566L))
            advanceUntilIdle()
            val state3 = viewModel.uiState.first { it.customTertiaryColor == 0xFF445566L }
            assertEquals(0xFF445566L, state3.customTertiaryColor)

            viewModel.onEvent(SettingsEvent.CustomPrimaryColorChanged(0xFF556677L))
            advanceUntilIdle()
            val state4 = viewModel.uiState.first { it.customPrimaryColor == 0xFF556677L }
            assertEquals(0xFF556677L, state4.customPrimaryColor)

            viewModel.viewModelScope.cancel()
        }

    @Test
    fun `SyncSettingsViewModel resync event enqueues worker`() =
        runTest {
            val mockSettingsRepo =
                mockk<SettingsRepository>(relaxed = true) {
                    every { userPreferences } returns flowOf(UserPreferences())
                    coEvery { getAvailableDevices() } returns emptyList()
                }
            val mockRefresh = mockk<HealthDataRefresh>(relaxed = true)
            val mockHistoricalResyncController =
                mockk<HistoricalResyncController>(relaxed = true) {
                    every { state } returns flowOf(HistoricalResyncState(running = false, current = 0, total = 0))
                }

            val viewModel =
                SyncSettingsViewModel(
                    mockSettingsRepo,
                    mockSettingsRepo,
                    mockSettingsRepo,
                    mockRefresh,
                    mockHistoricalResyncController,
                )
            viewModel.sharingStarted = SharingStarted.Lazily

            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.uiState.collect()
            }

            assertFalse(viewModel.uiState.value.isResyncing)
            viewModel.onEvent(SettingsEvent.ResyncHealthConnect)
            advanceUntilIdle()

            io.mockk.coVerify { mockHistoricalResyncController.requestHistoricalResync() }
        }
}
