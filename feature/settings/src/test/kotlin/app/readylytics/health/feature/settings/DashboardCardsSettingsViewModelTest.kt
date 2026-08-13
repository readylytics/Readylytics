package app.readylytics.health.feature.settings

import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.domain.dashboard.CardConfiguration
import app.readylytics.health.domain.dashboard.CardConfigurationRepository
import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.domain.preferences.DisplaySettings
import app.readylytics.health.domain.preferences.UserPreferencesReader
import app.readylytics.health.domain.vitals.VitalsLayoutRepository
import io.mockk.coEvery
import io.mockk.coJustRun
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

    private data class Harness(
        val viewModel: DashboardCardsSettingsViewModel,
        val dashboardConfigs: MutableStateFlow<List<CardConfiguration>>,
        val vitalsConfigs: MutableStateFlow<List<CardConfiguration>>,
        val displaySettings: DisplaySettings,
    )

    private fun buildViewModel(
        noticeDismissed: Boolean = false,
        currentGlobalMode: DashboardCardDisplayMode? = null,
        initialConfigs: List<CardConfiguration> =
            listOf(
                CardConfiguration(cardId = CardId.SLEEP_SCORE, requestedDisplayMode = null),
                CardConfiguration(cardId = CardId.HEART_RATE, requestedDisplayMode = null),
            ),
        initialVitalsConfigs: List<CardConfiguration> =
            listOf(CardConfiguration(cardId = CardId.SLEEP_RHR, requestedDisplayMode = null)),
    ): Harness {
        val prefsFlow =
            MutableStateFlow(
                UserPreferences(
                    bulkDisplayModeNoticeDismissed = noticeDismissed,
                    lastGlobalDisplayMode = currentGlobalMode,
                ),
            )
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
        val vitalsConfigsFlow = MutableStateFlow(initialVitalsConfigs)
        val vitalsLayoutRepository =
            mockk<VitalsLayoutRepository> {
                every { vitalsCardConfigurations() } returns vitalsConfigsFlow
                coEvery { updateVitalsCardConfigurations(any()) } coAnswers {
                    @Suppress("UNCHECKED_CAST")
                    vitalsConfigsFlow.value = it.invocation.args[0] as List<CardConfiguration>
                }
            }
        val displaySettings = mockk<DisplaySettings>(relaxed = true)

        val viewModel =
            DashboardCardsSettingsViewModel(
                settingsReader,
                displaySettings,
                cardConfigurationRepository,
                vitalsLayoutRepository,
            )
        viewModel.sharingStarted = SharingStarted.Lazily
        return Harness(viewModel, configsFlow, vitalsConfigsFlow, displaySettings)
    }

    @Test
    fun `apply when notice already dismissed writes immediately without showing the dialog`() =
        runTest(testDispatcher) {
            val (viewModel, configsFlow, vitalsConfigsFlow, _) = buildViewModel(noticeDismissed = true)
            val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }

            viewModel.onEvent(SettingsEvent.DashboardGlobalDisplayModeApplyRequested(DashboardCardDisplayMode.GAUGE))
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.showGlobalDisplayModeDialog)
            assertEquals(
                DashboardCardDisplayMode.GAUGE,
                configsFlow.value.first { it.cardId == CardId.SLEEP_SCORE }.requestedDisplayMode,
            )
            assertNull(configsFlow.value.first { it.cardId == CardId.HEART_RATE }.requestedDisplayMode)
            assertEquals(
                DashboardCardDisplayMode.GAUGE,
                vitalsConfigsFlow.value.first { it.cardId == CardId.SLEEP_RHR }.requestedDisplayMode,
            )

            job.cancel()
        }

    @Test
    fun `apply when notice not dismissed shows the confirm dialog and does not write yet`() =
        runTest(testDispatcher) {
            val (viewModel, configsFlow, _, _) = buildViewModel(noticeDismissed = false)
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
            val (viewModel, configsFlow, _, _) = buildViewModel(noticeDismissed = false)
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
            val (viewModel, _, _, displaySettings) = buildViewModel(noticeDismissed = false)
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
            val (viewModel, configsFlow, _, _) = buildViewModel(noticeDismissed = false)
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
            val vitalsLayoutRepository =
                mockk<VitalsLayoutRepository> {
                    every { vitalsCardConfigurations() } returns MutableStateFlow(emptyList())
                    coJustRun { updateVitalsCardConfigurations(any()) }
                }

            val viewModel =
                DashboardCardsSettingsViewModel(
                    settingsReader,
                    displaySettings,
                    cardConfigurationRepository,
                    vitalsLayoutRepository,
                )
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

    @Test
    fun `uiState reflects the persisted current global mode`() =
        runTest(testDispatcher) {
            val (viewModel, _, _, _) = buildViewModel(currentGlobalMode = DashboardCardDisplayMode.GAUGE)
            val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }

            advanceUntilIdle()

            assertEquals(DashboardCardDisplayMode.GAUGE, viewModel.uiState.value.currentGlobalMode)

            job.cancel()
        }

    @Test
    fun `apply persists the applied mode as the new current global mode`() =
        runTest(testDispatcher) {
            val (viewModel, _, _, displaySettings) = buildViewModel(noticeDismissed = true)
            val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }

            viewModel.onEvent(SettingsEvent.DashboardGlobalDisplayModeApplyRequested(DashboardCardDisplayMode.BAR))
            advanceUntilIdle()

            coVerify { displaySettings.updateLastGlobalDisplayMode(DashboardCardDisplayMode.BAR) }

            job.cancel()
        }

    @Test
    fun `reset when notice already dismissed clears every card and the current mode`() =
        runTest(testDispatcher) {
            val (viewModel, configsFlow, vitalsConfigsFlow, displaySettings) =
                buildViewModel(
                    noticeDismissed = true,
                    currentGlobalMode = DashboardCardDisplayMode.GAUGE,
                    initialConfigs =
                        listOf(
                            CardConfiguration(
                                cardId = CardId.SLEEP_SCORE,
                                requestedDisplayMode = DashboardCardDisplayMode.GAUGE,
                            ),
                        ),
                    initialVitalsConfigs =
                        listOf(
                            CardConfiguration(
                                cardId = CardId.SLEEP_RHR,
                                requestedDisplayMode = DashboardCardDisplayMode.GAUGE,
                            ),
                        ),
                )
            val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }

            viewModel.onEvent(SettingsEvent.DashboardGlobalDisplayModeResetRequested)
            advanceUntilIdle()

            assertNull(configsFlow.value.first { it.cardId == CardId.SLEEP_SCORE }.requestedDisplayMode)
            assertNull(vitalsConfigsFlow.value.first { it.cardId == CardId.SLEEP_RHR }.requestedDisplayMode)
            coVerify { displaySettings.updateLastGlobalDisplayMode(null) }

            job.cancel()
        }

    @Test
    fun `reset when notice not dismissed shows the confirm dialog flagged as a reset`() =
        runTest(testDispatcher) {
            val (viewModel, configsFlow, _, _) =
                buildViewModel(
                    noticeDismissed = false,
                    initialConfigs =
                        listOf(
                            CardConfiguration(
                                cardId = CardId.SLEEP_SCORE,
                                requestedDisplayMode = DashboardCardDisplayMode.GAUGE,
                            ),
                        ),
                )
            val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }

            viewModel.onEvent(SettingsEvent.DashboardGlobalDisplayModeResetRequested)
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.showGlobalDisplayModeDialog)
            assertTrue(viewModel.uiState.value.pendingIsReset)
            assertEquals(
                DashboardCardDisplayMode.GAUGE,
                configsFlow.value.first { it.cardId == CardId.SLEEP_SCORE }.requestedDisplayMode,
            )

            job.cancel()
        }

    @Test
    fun `confirming a pending reset resets and clears the reset flag`() =
        runTest(testDispatcher) {
            val (viewModel, configsFlow, _, _) =
                buildViewModel(
                    noticeDismissed = false,
                    initialConfigs =
                        listOf(
                            CardConfiguration(
                                cardId = CardId.SLEEP_SCORE,
                                requestedDisplayMode = DashboardCardDisplayMode.GAUGE,
                            ),
                        ),
                )
            val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }

            viewModel.onEvent(SettingsEvent.DashboardGlobalDisplayModeResetRequested)
            advanceUntilIdle()
            viewModel.onEvent(SettingsEvent.DashboardGlobalDisplayModeConfirmed(dontShowAgain = false))
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.pendingIsReset)
            assertFalse(viewModel.uiState.value.showGlobalDisplayModeDialog)
            assertNull(configsFlow.value.first { it.cardId == CardId.SLEEP_SCORE }.requestedDisplayMode)

            job.cancel()
        }

    @Test
    fun `apply and reset share one in-flight guard`() {
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
                        delay(1) // Ensure the coroutine actually suspends, opening a race window
                        @Suppress("UNCHECKED_CAST")
                        configsFlow.value = it.invocation.args[0] as List<CardConfiguration>
                    }
                }
            val displaySettings = mockk<DisplaySettings>(relaxed = true)
            val vitalsLayoutRepository =
                mockk<VitalsLayoutRepository> {
                    every { vitalsCardConfigurations() } returns MutableStateFlow(emptyList())
                    coJustRun { updateVitalsCardConfigurations(any()) }
                }

            val viewModel =
                DashboardCardsSettingsViewModel(
                    settingsReader,
                    displaySettings,
                    cardConfigurationRepository,
                    vitalsLayoutRepository,
                )
            viewModel.sharingStarted = SharingStarted.Lazily

            val job = backgroundScope.launch { viewModel.uiState.collect() }

            // Dispatch Apply, then Reset, before Apply's write has finished suspending on delay(1).
            // If applyJob were NOT shared between the two event branches, Reset would launch its own
            // coroutine and a second write would happen.
            viewModel.onEvent(SettingsEvent.DashboardGlobalDisplayModeApplyRequested(DashboardCardDisplayMode.BAR))
            viewModel.onEvent(SettingsEvent.DashboardGlobalDisplayModeResetRequested)

            advanceUntilIdle()

            // Only Apply's write should have happened: Reset was blocked by the shared applyJob guard
            // while Apply's coroutine was still in flight.
            coVerify(exactly = 1) { cardConfigurationRepository.updateDashboardCardConfigurations(any()) }
            assertEquals(
                DashboardCardDisplayMode.BAR,
                configsFlow.value.first { it.cardId == CardId.SLEEP_SCORE }.requestedDisplayMode,
            )
            assertFalse(viewModel.uiState.value.pendingIsReset)
            assertFalse(viewModel.uiState.value.showGlobalDisplayModeDialog)

            job.cancel()
        }
    }
}
