package app.readylytics.health.feature.settings

import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.domain.dashboard.CardConfiguration
import app.readylytics.health.domain.dashboard.CardConfigurationRepository
import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.domain.preferences.DisplaySettings
import app.readylytics.health.domain.preferences.UserPreferencesReader
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardCardsSettingsViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel(
        noticeDismissed: Boolean = false,
        initialConfigs: List<CardConfiguration> =
            listOf(
                CardConfiguration(cardId = CardId.SLEEP_SCORE, requestedDisplayMode = null),
                CardConfiguration(cardId = CardId.HEART_RATE, requestedDisplayMode = null),
            ),
    ): Triple<DashboardCardsSettingsViewModel, MutableStateFlow<List<CardConfiguration>>, DisplaySettings> {
        val prefsFlow = MutableStateFlow(UserPreferences(bulkDisplayModeNoticeDismissed = noticeDismissed))
        val settingsReader =
            mockk<UserPreferencesReader> {
                every { userPreferences } returns prefsFlow
            }
        val configsFlow = MutableStateFlow(initialConfigs)
        val cardConfigurationRepository =
            mockk<CardConfigurationRepository> {
                every { dashboardCardConfigurations() } returns configsFlow
                coEvery { updateDashboardCardConfigurations(any()) } coAnswers {
                    @Suppress("UNCHECKED_CAST")
                    configsFlow.value = it.invocation.args[0] as List<CardConfiguration>
                }
            }
        val displaySettings = mockk<DisplaySettings>(relaxed = true)

        val viewModel = DashboardCardsSettingsViewModel(settingsReader, displaySettings, cardConfigurationRepository)
        viewModel.sharingStarted = SharingStarted.Lazily
        return Triple(viewModel, configsFlow, displaySettings)
    }

    @Test
    fun `apply when notice already dismissed writes immediately without showing the dialog`() =
        runTest(testDispatcher) {
            val (viewModel, configsFlow, _) = buildViewModel(noticeDismissed = true)
            val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }

            viewModel.onEvent(SettingsEvent.DashboardGlobalDisplayModeApplyRequested(DashboardCardDisplayMode.GAUGE))
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.showGlobalDisplayModeDialog)
            assertEquals(
                DashboardCardDisplayMode.GAUGE,
                configsFlow.value.first { it.cardId == CardId.SLEEP_SCORE }.requestedDisplayMode,
            )
            assertNull(configsFlow.value.first { it.cardId == CardId.HEART_RATE }.requestedDisplayMode)

            job.cancel()
        }

    @Test
    fun `apply when notice not dismissed shows the confirm dialog and does not write yet`() =
        runTest(testDispatcher) {
            val (viewModel, configsFlow, _) = buildViewModel(noticeDismissed = false)
            val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }

            viewModel.onEvent(SettingsEvent.DashboardGlobalDisplayModeApplyRequested(DashboardCardDisplayMode.BAR))
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.showGlobalDisplayModeDialog)
            assertNull(configsFlow.value.first { it.cardId == CardId.SLEEP_SCORE }.requestedDisplayMode)

            job.cancel()
        }

    @Test
    fun `confirming the dialog writes the mode and hides the dialog`() =
        runTest(testDispatcher) {
            val (viewModel, configsFlow, _) = buildViewModel(noticeDismissed = false)
            val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }

            viewModel.onEvent(SettingsEvent.DashboardGlobalDisplayModeApplyRequested(DashboardCardDisplayMode.BAR))
            advanceUntilIdle()
            viewModel.onEvent(SettingsEvent.DashboardGlobalDisplayModeConfirmed(dontShowAgain = false))
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.showGlobalDisplayModeDialog)
            assertEquals(
                DashboardCardDisplayMode.BAR,
                configsFlow.value.first { it.cardId == CardId.SLEEP_SCORE }.requestedDisplayMode,
            )

            job.cancel()
        }

    @Test
    fun `confirming with dont show again persists the notice flag`() =
        runTest(testDispatcher) {
            val (viewModel, _, displaySettings) = buildViewModel(noticeDismissed = false)
            val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }

            viewModel.onEvent(SettingsEvent.DashboardGlobalDisplayModeApplyRequested(DashboardCardDisplayMode.VALUE))
            advanceUntilIdle()
            viewModel.onEvent(SettingsEvent.DashboardGlobalDisplayModeConfirmed(dontShowAgain = true))
            advanceUntilIdle()

            coVerify { displaySettings.updateBulkDisplayModeNoticeDismissed(true) }

            job.cancel()
        }

    @Test
    fun `dismissing the dialog does not write any changes`() =
        runTest(testDispatcher) {
            val (viewModel, configsFlow, _) = buildViewModel(noticeDismissed = false)
            val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }

            viewModel.onEvent(SettingsEvent.DashboardGlobalDisplayModeApplyRequested(DashboardCardDisplayMode.BAR))
            advanceUntilIdle()
            viewModel.onEvent(SettingsEvent.DashboardGlobalDisplayModeDialogDismissed)
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.showGlobalDisplayModeDialog)
            assertNull(configsFlow.value.first { it.cardId == CardId.SLEEP_SCORE }.requestedDisplayMode)

            job.cancel()
        }

    @Test
    fun `rapid double-apply is guarded and only writes once`() {
        val standardDispatcher = StandardTestDispatcher()
        runTest(standardDispatcher) {
            val prefsFlow = MutableStateFlow(UserPreferences(bulkDisplayModeNoticeDismissed = true))
            val settingsReader =
                mockk<UserPreferencesReader> {
                    every { userPreferences } returns prefsFlow
                }
            val configsFlow =
                MutableStateFlow(
                    listOf(
                        CardConfiguration(cardId = CardId.SLEEP_SCORE, requestedDisplayMode = null),
                        CardConfiguration(cardId = CardId.HEART_RATE, requestedDisplayMode = null),
                    ),
                )
            val cardConfigurationRepository =
                mockk<CardConfigurationRepository> {
                    every { dashboardCardConfigurations() } returns configsFlow
                    coEvery { updateDashboardCardConfigurations(any()) } coAnswers {
                        delay(1) // Ensure the coroutine actually suspends
                        @Suppress("UNCHECKED_CAST")
                        configsFlow.value = it.invocation.args[0] as List<CardConfiguration>
                    }
                }
            val displaySettings = mockk<DisplaySettings>(relaxed = true)

            val viewModel =
                DashboardCardsSettingsViewModel(settingsReader, displaySettings, cardConfigurationRepository)
            viewModel.sharingStarted = SharingStarted.Lazily

            val job = backgroundScope.launch { viewModel.uiState.collect() }

            // Dispatch apply twice in rapid succession without advancing time between them
            viewModel.onEvent(SettingsEvent.DashboardGlobalDisplayModeApplyRequested(DashboardCardDisplayMode.GAUGE))
            viewModel.onEvent(SettingsEvent.DashboardGlobalDisplayModeApplyRequested(DashboardCardDisplayMode.GAUGE))

            // Now let pending coroutines finish
            advanceUntilIdle()

            // Only one write should have happened despite two event dispatches
            coVerify(exactly = 1) { cardConfigurationRepository.updateDashboardCardConfigurations(any()) }

            job.cancel()
        }
    }
}
