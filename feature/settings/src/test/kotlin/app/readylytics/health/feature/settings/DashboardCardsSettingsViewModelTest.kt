package app.readylytics.health.feature.settings

import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.dashboard.CardConfiguration
import app.readylytics.health.core.model.domain.dashboard.CardConfigurationRepository
import app.readylytics.health.core.model.domain.dashboard.CardId
import app.readylytics.health.core.model.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.core.model.domain.preferences.DisplaySettings
import app.readylytics.health.core.model.domain.preferences.UserPreferencesReader
import app.readylytics.health.core.model.domain.sleep.SleepLayoutRepository
import app.readylytics.health.core.model.domain.sleep.SleepMetricCardConfiguration
import app.readylytics.health.core.model.domain.sleep.SleepMetricCardId
import app.readylytics.health.core.model.domain.sleep.SleepTopCardConfiguration
import app.readylytics.health.core.model.domain.sleep.SleepTopCardId
import app.readylytics.health.core.model.domain.vitals.VitalsLayoutRepository
import app.readylytics.health.core.model.domain.workouts.WorkoutsLayoutRepository
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
        val sleepTopCards: MutableStateFlow<List<SleepTopCardConfiguration>>,
        val sleepMetricCards: MutableStateFlow<List<SleepMetricCardConfiguration>>,
        val workoutConfigs: MutableStateFlow<List<CardConfiguration>>,
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
            listOf(CardConfiguration(cardId = CardId.RESTING_HR, requestedDisplayMode = null)),
        initialSleepTopCards: List<SleepTopCardConfiguration> = emptyList(),
        initialSleepMetricCards: List<SleepMetricCardConfiguration> = emptyList(),
        initialWorkoutConfigs: List<CardConfiguration> = emptyList(),
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
        val sleepTopCardsFlow = MutableStateFlow(initialSleepTopCards)
        val sleepMetricCardsFlow = MutableStateFlow(initialSleepMetricCards)
        val sleepLayoutRepository =
            mockk<SleepLayoutRepository> {
                every { sleepTopCardConfigurations() } returns sleepTopCardsFlow
                every { sleepMetricCardConfigurations() } returns sleepMetricCardsFlow
                coEvery { updateSleepTopCardConfigurations(any()) } coAnswers {
                    @Suppress("UNCHECKED_CAST")
                    sleepTopCardsFlow.value = it.invocation.args[0] as List<SleepTopCardConfiguration>
                }
                coEvery { updateSleepMetricCardConfigurations(any()) } coAnswers {
                    @Suppress("UNCHECKED_CAST")
                    sleepMetricCardsFlow.value = it.invocation.args[0] as List<SleepMetricCardConfiguration>
                }
            }
        val workoutConfigsFlow = MutableStateFlow(initialWorkoutConfigs)
        val workoutsLayoutRepository =
            mockk<WorkoutsLayoutRepository> {
                every { workoutCardConfigurations() } returns workoutConfigsFlow
                coEvery { updateWorkoutCardConfigurations(any()) } coAnswers {
                    @Suppress("UNCHECKED_CAST")
                    workoutConfigsFlow.value = it.invocation.args[0] as List<CardConfiguration>
                }
            }
        val displaySettings = mockk<DisplaySettings>(relaxed = true)

        val viewModel =
            DashboardCardsSettingsViewModel(
                settingsReader,
                displaySettings,
                cardConfigurationRepository,
                vitalsLayoutRepository,
                sleepLayoutRepository,
                workoutsLayoutRepository,
            )
        viewModel.sharingStarted = SharingStarted.Lazily
        return Harness(
            viewModel,
            configsFlow,
            vitalsConfigsFlow,
            displaySettings,
            sleepTopCardsFlow,
            sleepMetricCardsFlow,
            workoutConfigsFlow,
        )
    }

    @Test
    fun `apply when notice already dismissed writes immediately without showing the dialog`() =
        runTest(testDispatcher) {
            val harness = buildViewModel(noticeDismissed = true)
            val viewModel = harness.viewModel
            val configsFlow = harness.dashboardConfigs
            val vitalsConfigsFlow = harness.vitalsConfigs
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
                vitalsConfigsFlow.value.first { it.cardId == CardId.RESTING_HR }.requestedDisplayMode,
            )

            job.cancel()
        }

    @Test
    fun `apply when notice not dismissed shows the confirm dialog and does not write yet`() =
        runTest(testDispatcher) {
            val harness = buildViewModel(noticeDismissed = false)
            val viewModel = harness.viewModel
            val configsFlow = harness.dashboardConfigs
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
            val harness = buildViewModel(noticeDismissed = false)
            val viewModel = harness.viewModel
            val configsFlow = harness.dashboardConfigs
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
            val harness = buildViewModel(noticeDismissed = false)
            val viewModel = harness.viewModel
            val displaySettings = harness.displaySettings
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
            val harness = buildViewModel(noticeDismissed = false)
            val viewModel = harness.viewModel
            val configsFlow = harness.dashboardConfigs
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
            val sleepLayoutRepository =
                mockk<SleepLayoutRepository> {
                    every { sleepTopCardConfigurations() } returns MutableStateFlow(emptyList())
                    every { sleepMetricCardConfigurations() } returns MutableStateFlow(emptyList())
                    coJustRun { updateSleepTopCardConfigurations(any()) }
                    coJustRun { updateSleepMetricCardConfigurations(any()) }
                }
            val workoutsLayoutRepository =
                mockk<WorkoutsLayoutRepository> {
                    every { workoutCardConfigurations() } returns MutableStateFlow(emptyList())
                    coJustRun { updateWorkoutCardConfigurations(any()) }
                }

            val viewModel =
                DashboardCardsSettingsViewModel(
                    settingsReader,
                    displaySettings,
                    cardConfigurationRepository,
                    vitalsLayoutRepository,
                    sleepLayoutRepository,
                    workoutsLayoutRepository,
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
            val harness = buildViewModel(currentGlobalMode = DashboardCardDisplayMode.GAUGE)
            val viewModel = harness.viewModel
            val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }

            advanceUntilIdle()

            assertEquals(DashboardCardDisplayMode.GAUGE, viewModel.uiState.value.currentGlobalMode)

            job.cancel()
        }

    @Test
    fun `apply persists the applied mode as the new current global mode`() =
        runTest(testDispatcher) {
            val harness = buildViewModel(noticeDismissed = true)
            val viewModel = harness.viewModel
            val displaySettings = harness.displaySettings
            val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }

            viewModel.onEvent(SettingsEvent.DashboardGlobalDisplayModeApplyRequested(DashboardCardDisplayMode.BAR))
            advanceUntilIdle()

            coVerify { displaySettings.updateLastGlobalDisplayMode(DashboardCardDisplayMode.BAR) }

            job.cancel()
        }

    @Test
    fun `reset when notice already dismissed clears every card and the current mode`() =
        runTest(testDispatcher) {
            val harness =
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
                                cardId = CardId.RESTING_HR,
                                requestedDisplayMode = DashboardCardDisplayMode.BAR,
                            ),
                        ),
                )
            val viewModel = harness.viewModel
            val configsFlow = harness.dashboardConfigs
            val vitalsConfigsFlow = harness.vitalsConfigs
            val displaySettings = harness.displaySettings
            val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect() }

            viewModel.onEvent(SettingsEvent.DashboardGlobalDisplayModeResetRequested)
            advanceUntilIdle()

            assertNull(configsFlow.value.first { it.cardId == CardId.SLEEP_SCORE }.requestedDisplayMode)
            assertEquals(
                DashboardCardDisplayMode.GAUGE,
                vitalsConfigsFlow.value.first { it.cardId == CardId.RESTING_HR }.requestedDisplayMode,
            )
            coVerify { displaySettings.updateLastGlobalDisplayMode(null) }

            job.cancel()
        }

    @Test
    fun `reset when notice not dismissed shows the confirm dialog flagged as a reset`() =
        runTest(testDispatcher) {
            val harness =
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
            val viewModel = harness.viewModel
            val configsFlow = harness.dashboardConfigs
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
            val harness =
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
            val viewModel = harness.viewModel
            val configsFlow = harness.dashboardConfigs
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
            val sleepLayoutRepository =
                mockk<SleepLayoutRepository> {
                    every { sleepTopCardConfigurations() } returns MutableStateFlow(emptyList())
                    every { sleepMetricCardConfigurations() } returns MutableStateFlow(emptyList())
                    coJustRun { updateSleepTopCardConfigurations(any()) }
                    coJustRun { updateSleepMetricCardConfigurations(any()) }
                }
            val workoutsLayoutRepository =
                mockk<WorkoutsLayoutRepository> {
                    every { workoutCardConfigurations() } returns MutableStateFlow(emptyList())
                    coJustRun { updateWorkoutCardConfigurations(any()) }
                }

            val viewModel =
                DashboardCardsSettingsViewModel(
                    settingsReader,
                    displaySettings,
                    cardConfigurationRepository,
                    vitalsLayoutRepository,
                    sleepLayoutRepository,
                    workoutsLayoutRepository,
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

    @Test
    fun `apply also sets sleep top cards and metric cards`() =
        runTest(testDispatcher) {
            val harness =
                buildViewModel(
                    noticeDismissed = true,
                    initialSleepTopCards =
                        listOf(
                            SleepTopCardConfiguration(SleepTopCardId.SLEEP_SCORE, requestedDisplayMode = null),
                            SleepTopCardConfiguration(SleepTopCardId.SLEEP_BREAKDOWN_BAR, requestedDisplayMode = null),
                        ),
                    initialSleepMetricCards =
                        listOf(
                            SleepMetricCardConfiguration(SleepMetricCardId.DEEP_SLEEP, requestedDisplayMode = null),
                            SleepMetricCardConfiguration(SleepMetricCardId.NAP_DURATION, requestedDisplayMode = null),
                        ),
                )
            val job =
                backgroundScope.launch(
                    UnconfinedTestDispatcher(testScheduler),
                ) { harness.viewModel.uiState.collect() }

            harness.viewModel.onEvent(
                SettingsEvent.DashboardGlobalDisplayModeApplyRequested(DashboardCardDisplayMode.GAUGE),
            )
            advanceUntilIdle()

            assertEquals(
                DashboardCardDisplayMode.GAUGE,
                harness.sleepTopCards.value
                    .first { it.cardId == SleepTopCardId.SLEEP_SCORE }
                    .requestedDisplayMode,
            )
            assertNull(
                harness.sleepTopCards.value
                    .first { it.cardId == SleepTopCardId.SLEEP_BREAKDOWN_BAR }
                    .requestedDisplayMode,
            )
            assertEquals(
                DashboardCardDisplayMode.GAUGE,
                harness.sleepMetricCards.value
                    .first { it.cardId == SleepMetricCardId.DEEP_SLEEP }
                    .requestedDisplayMode,
            )
            assertNull(
                harness.sleepMetricCards.value
                    .first { it.cardId == SleepMetricCardId.NAP_DURATION }
                    .requestedDisplayMode,
            )

            job.cancel()
        }

    @Test
    fun `reset also clears sleep top cards and metric cards`() =
        runTest(testDispatcher) {
            val harness =
                buildViewModel(
                    noticeDismissed = true,
                    initialSleepTopCards =
                        listOf(
                            SleepTopCardConfiguration(
                                SleepTopCardId.SLEEP_SCORE,
                                requestedDisplayMode = DashboardCardDisplayMode.GAUGE,
                            ),
                        ),
                    initialSleepMetricCards =
                        listOf(
                            SleepMetricCardConfiguration(
                                SleepMetricCardId.DEEP_SLEEP,
                                requestedDisplayMode = DashboardCardDisplayMode.BAR,
                            ),
                        ),
                )
            val job =
                backgroundScope.launch(
                    UnconfinedTestDispatcher(testScheduler),
                ) { harness.viewModel.uiState.collect() }

            harness.viewModel.onEvent(SettingsEvent.DashboardGlobalDisplayModeResetRequested)
            advanceUntilIdle()

            assertNull(
                harness.sleepTopCards.value
                    .first { it.cardId == SleepTopCardId.SLEEP_SCORE }
                    .requestedDisplayMode,
            )
            assertNull(
                harness.sleepMetricCards.value
                    .first { it.cardId == SleepMetricCardId.DEEP_SLEEP }
                    .requestedDisplayMode,
            )

            job.cancel()
        }

    @Test
    fun `apply also sets workout cards`() =
        runTest(testDispatcher) {
            val harness =
                buildViewModel(
                    noticeDismissed = true,
                    initialWorkoutConfigs =
                        listOf(
                            CardConfiguration(cardId = CardId.STRAIN_RATIO, requestedDisplayMode = null),
                            CardConfiguration(cardId = CardId.READINESS, requestedDisplayMode = null),
                        ),
                )
            val job =
                backgroundScope.launch(
                    UnconfinedTestDispatcher(testScheduler),
                ) { harness.viewModel.uiState.collect() }

            harness.viewModel.onEvent(
                SettingsEvent.DashboardGlobalDisplayModeApplyRequested(DashboardCardDisplayMode.GAUGE),
            )
            advanceUntilIdle()

            assertEquals(
                DashboardCardDisplayMode.GAUGE,
                harness.workoutConfigs.value
                    .first { it.cardId == CardId.STRAIN_RATIO }
                    .requestedDisplayMode,
            )
            assertEquals(
                DashboardCardDisplayMode.GAUGE,
                harness.workoutConfigs.value
                    .first { it.cardId == CardId.READINESS }
                    .requestedDisplayMode,
            )

            job.cancel()
        }

    @Test
    fun `reset also restores workout cards to their default modes`() =
        runTest(testDispatcher) {
            val harness =
                buildViewModel(
                    noticeDismissed = true,
                    initialWorkoutConfigs =
                        listOf(
                            CardConfiguration(
                                cardId = CardId.STRAIN_RATIO,
                                requestedDisplayMode = DashboardCardDisplayMode.BAR,
                            ),
                            CardConfiguration(
                                cardId = CardId.READINESS,
                                requestedDisplayMode = DashboardCardDisplayMode.BAR,
                            ),
                            CardConfiguration(
                                cardId = CardId.RAS_DAILY,
                                requestedDisplayMode = DashboardCardDisplayMode.GAUGE,
                            ),
                        ),
                )
            val job =
                backgroundScope.launch(
                    UnconfinedTestDispatcher(testScheduler),
                ) { harness.viewModel.uiState.collect() }

            harness.viewModel.onEvent(SettingsEvent.DashboardGlobalDisplayModeResetRequested)
            advanceUntilIdle()

            assertEquals(
                DashboardCardDisplayMode.GAUGE,
                harness.workoutConfigs.value
                    .first { it.cardId == CardId.STRAIN_RATIO }
                    .requestedDisplayMode,
            )
            assertEquals(
                DashboardCardDisplayMode.GAUGE,
                harness.workoutConfigs.value
                    .first { it.cardId == CardId.READINESS }
                    .requestedDisplayMode,
            )
            assertEquals(
                DashboardCardDisplayMode.VALUE,
                harness.workoutConfigs.value
                    .first { it.cardId == CardId.RAS_DAILY }
                    .requestedDisplayMode,
            )

            job.cancel()
        }
}
