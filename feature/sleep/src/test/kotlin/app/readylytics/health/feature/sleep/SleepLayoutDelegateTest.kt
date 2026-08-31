package app.readylytics.health.feature.sleep

import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import app.readylytics.health.core.model.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.core.model.domain.sleep.SleepChartId
import app.readylytics.health.core.model.domain.sleep.SleepLayoutRepository
import app.readylytics.health.core.model.domain.sleep.SleepMetricCardId
import app.readylytics.health.core.model.domain.sleep.SleepTopCardId
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
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

@OptIn(ExperimentalCoroutinesApi::class)
class SleepLayoutDelegateTest {
    private val testDispatcher = StandardTestDispatcher()
    private val sleepLayoutRepository: SleepLayoutRepository = mockk(relaxed = true)
    private val topCardsFlow = MutableStateFlow(SettingsDefaults.DEFAULT_SLEEP_TOP_CARDS)
    private val chartsFlow = MutableStateFlow(SettingsDefaults.DEFAULT_SLEEP_CHARTS)
    private val metricCardsFlow = MutableStateFlow(SettingsDefaults.DEFAULT_SLEEP_METRIC_CARDS)
    private lateinit var delegateScope: CoroutineScope
    private lateinit var delegate: SleepLayoutDelegate

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { sleepLayoutRepository.sleepTopCardConfigurations() } returns topCardsFlow
        every { sleepLayoutRepository.sleepChartConfigurations() } returns chartsFlow
        every { sleepLayoutRepository.sleepMetricCardConfigurations() } returns metricCardsFlow

        delegateScope = CoroutineScope(SupervisorJob() + testDispatcher)
        delegate = SleepLayoutDelegate(sleepLayoutRepository, delegateScope)
    }

    @After
    fun tearDown() {
        delegateScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state reflects repository values and default edit state`() =
        runTest(testDispatcher) {
            advanceUntilIdle()

            val state = delegate.layoutStateFlow.value
            assertEquals(SettingsDefaults.DEFAULT_SLEEP_TOP_CARDS, state.sleepTopCardConfigurations)
            assertEquals(SettingsDefaults.DEFAULT_SLEEP_CHARTS, state.sleepChartConfigurations)
            assertEquals(SettingsDefaults.DEFAULT_SLEEP_METRIC_CARDS, state.sleepMetricCardConfigurations)
            assertFalse(state.isManagingSleepTopCards)
            assertFalse(state.isManagingSleepCharts)
            assertFalse(state.isManagingSleepMetricCards)
            assertFalse(state.isManagingSleepLayout)
        }

    @Test
    fun `toggle sleep layout enters edit mode and persists on save`() =
        runTest(testDispatcher) {
            advanceUntilIdle()

            // Enter edit mode
            delegate.toggleSleepLayoutManagement(
                isManaging = false,
                currentTopCards = delegate.layoutStateFlow.value.sleepTopCardConfigurations,
                currentCharts = delegate.layoutStateFlow.value.sleepChartConfigurations,
                currentMetricCards = delegate.layoutStateFlow.value.sleepMetricCardConfigurations,
            )
            advanceUntilIdle()

            assertTrue(delegate.layoutStateFlow.value.isManagingSleepTopCards)
            assertTrue(delegate.layoutStateFlow.value.isManagingSleepCharts)
            assertTrue(delegate.layoutStateFlow.value.isManagingSleepMetricCards)
            assertTrue(delegate.layoutStateFlow.value.isManagingSleepLayout)

            // Hide Sleep Score top card
            delegate.onToggleSleepTopCardVisibility(
                currentConfigs = delegate.layoutStateFlow.value.sleepTopCardConfigurations,
                cardId = SleepTopCardId.SLEEP_SCORE,
                visible = false,
            )
            advanceUntilIdle()

            assertFalse(
                delegate.layoutStateFlow.value.sleepTopCardConfigurations
                    .first { it.cardId == SleepTopCardId.SLEEP_SCORE }
                    .isVisible,
            )

            // Save changes
            delegate.toggleSleepLayoutManagement(
                isManaging = true,
                currentTopCards = delegate.layoutStateFlow.value.sleepTopCardConfigurations,
                currentCharts = delegate.layoutStateFlow.value.sleepChartConfigurations,
                currentMetricCards = delegate.layoutStateFlow.value.sleepMetricCardConfigurations,
            )
            advanceUntilIdle()

            assertFalse(delegate.layoutStateFlow.value.isManagingSleepLayout)
            coVerify {
                sleepLayoutRepository.updateSleepTopCardConfigurations(
                    match { configs ->
                        configs.any { it.cardId == SleepTopCardId.SLEEP_SCORE && !it.isVisible }
                    },
                )
            }
        }

    @Test
    fun `cancel sleep layout management discards pending changes without persisting`() =
        runTest(testDispatcher) {
            advanceUntilIdle()

            delegate.toggleSleepLayoutManagement(
                isManaging = false,
                currentTopCards = delegate.layoutStateFlow.value.sleepTopCardConfigurations,
                currentCharts = delegate.layoutStateFlow.value.sleepChartConfigurations,
                currentMetricCards = delegate.layoutStateFlow.value.sleepMetricCardConfigurations,
            )
            advanceUntilIdle()

            delegate.onToggleSleepTopCardVisibility(
                currentConfigs = delegate.layoutStateFlow.value.sleepTopCardConfigurations,
                cardId = SleepTopCardId.SLEEP_SCORE,
                visible = false,
            )
            delegate.onToggleSleepMetricCardVisibility(
                currentConfigs = delegate.layoutStateFlow.value.sleepMetricCardConfigurations,
                cardId = SleepMetricCardId.CIRCADIAN_CONSISTENCY,
                visible = false,
            )
            advanceUntilIdle()

            delegate.onCancelSleepLayoutManagement()
            advanceUntilIdle()

            assertFalse(delegate.layoutStateFlow.value.isManagingSleepLayout)
            assertTrue(
                delegate.layoutStateFlow.value.sleepTopCardConfigurations
                    .all { it.isVisible },
            )
            assertTrue(
                delegate.layoutStateFlow.value.sleepMetricCardConfigurations
                    .all { it.isVisible },
            )
            coVerify(exactly = 0) {
                sleepLayoutRepository.updateSleepTopCardConfigurations(any())
            }
            coVerify(exactly = 0) {
                sleepLayoutRepository.updateSleepMetricCardConfigurations(any())
            }
        }

    @Test
    fun `reset sleep layout to defaults restores defaults across top cards, charts, and metrics`() =
        runTest(testDispatcher) {
            advanceUntilIdle()

            delegate.toggleSleepLayoutManagement(
                isManaging = false,
                currentTopCards = delegate.layoutStateFlow.value.sleepTopCardConfigurations,
                currentCharts = delegate.layoutStateFlow.value.sleepChartConfigurations,
                currentMetricCards = delegate.layoutStateFlow.value.sleepMetricCardConfigurations,
            )
            advanceUntilIdle()

            delegate.onResetSleepLayoutToDefaults()
            advanceUntilIdle()

            assertEquals(
                SettingsDefaults.DEFAULT_SLEEP_TOP_CARDS,
                delegate.layoutStateFlow.value.sleepTopCardConfigurations,
            )
            assertEquals(SettingsDefaults.DEFAULT_SLEEP_CHARTS, delegate.layoutStateFlow.value.sleepChartConfigurations)
            assertEquals(
                SettingsDefaults.DEFAULT_SLEEP_METRIC_CARDS,
                delegate.layoutStateFlow.value.sleepMetricCardConfigurations,
            )

            delegate.toggleSleepLayoutManagement(
                isManaging = true,
                currentTopCards = delegate.layoutStateFlow.value.sleepTopCardConfigurations,
                currentCharts = delegate.layoutStateFlow.value.sleepChartConfigurations,
                currentMetricCards = delegate.layoutStateFlow.value.sleepMetricCardConfigurations,
            )
            advanceUntilIdle()

            coVerify {
                sleepLayoutRepository.updateSleepTopCardConfigurations(SettingsDefaults.DEFAULT_SLEEP_TOP_CARDS)
            }
            coVerify {
                sleepLayoutRepository.updateSleepChartConfigurations(SettingsDefaults.DEFAULT_SLEEP_CHARTS)
            }
            coVerify {
                sleepLayoutRepository.updateSleepMetricCardConfigurations(SettingsDefaults.DEFAULT_SLEEP_METRIC_CARDS)
            }
        }

    @Test
    fun `reorder sleep metric cards updates pending order`() =
        runTest(testDispatcher) {
            advanceUntilIdle()

            delegate.toggleSleepLayoutManagement(
                isManaging = false,
                currentTopCards = delegate.layoutStateFlow.value.sleepTopCardConfigurations,
                currentCharts = delegate.layoutStateFlow.value.sleepChartConfigurations,
                currentMetricCards = delegate.layoutStateFlow.value.sleepMetricCardConfigurations,
            )
            advanceUntilIdle()

            val current = delegate.layoutStateFlow.value.sleepMetricCardConfigurations
            val newOrder = listOf(current[1], current[0]) + current.drop(2)
            delegate.onReorderSleepMetricCards(current, newOrder)
            advanceUntilIdle()

            assertEquals(
                SleepMetricCardId.SLEEP_EFFICIENCY,
                delegate.layoutStateFlow.value.sleepMetricCardConfigurations[0]
                    .cardId,
            )
            assertEquals(
                SleepMetricCardId.CIRCADIAN_CONSISTENCY,
                delegate.layoutStateFlow.value.sleepMetricCardConfigurations[1]
                    .cardId,
            )
        }

    @Test
    fun `display mode changes update pending configurations`() =
        runTest(testDispatcher) {
            advanceUntilIdle()

            delegate.toggleSleepLayoutManagement(
                isManaging = false,
                currentTopCards = delegate.layoutStateFlow.value.sleepTopCardConfigurations,
                currentCharts = delegate.layoutStateFlow.value.sleepChartConfigurations,
                currentMetricCards = delegate.layoutStateFlow.value.sleepMetricCardConfigurations,
            )
            advanceUntilIdle()

            delegate.onDisplayModeChanged(
                topCardId = SleepTopCardId.SLEEP_SCORE,
                mode = DashboardCardDisplayMode.BAR,
            )
            delegate.onDisplayModeChanged(
                metricCardId = SleepMetricCardId.SLEEP_EFFICIENCY,
                mode = DashboardCardDisplayMode.GAUGE,
            )
            advanceUntilIdle()

            assertEquals(
                DashboardCardDisplayMode.BAR,
                delegate.layoutStateFlow.value.sleepTopCardConfigurations
                    .first { it.cardId == SleepTopCardId.SLEEP_SCORE }
                    .requestedDisplayMode,
            )
            assertEquals(
                DashboardCardDisplayMode.GAUGE,
                delegate.layoutStateFlow.value.sleepMetricCardConfigurations
                    .first { it.cardId == SleepMetricCardId.SLEEP_EFFICIENCY }
                    .requestedDisplayMode,
            )
        }

    @Test
    fun `chart visibility and reorder work as expected`() =
        runTest(testDispatcher) {
            advanceUntilIdle()

            delegate.toggleSleepLayoutManagement(
                isManaging = false,
                currentTopCards = delegate.layoutStateFlow.value.sleepTopCardConfigurations,
                currentCharts = delegate.layoutStateFlow.value.sleepChartConfigurations,
                currentMetricCards = delegate.layoutStateFlow.value.sleepMetricCardConfigurations,
            )
            advanceUntilIdle()

            delegate.onToggleSleepChartVisibility(
                currentConfigs = delegate.layoutStateFlow.value.sleepChartConfigurations,
                chartId = SleepChartId.SLEEP_DURATION_TREND,
                visible = false,
            )
            advanceUntilIdle()

            assertFalse(
                delegate.layoutStateFlow.value.sleepChartConfigurations
                    .first { it.chartId == SleepChartId.SLEEP_DURATION_TREND }
                    .isVisible,
            )

            val currentCharts = delegate.layoutStateFlow.value.sleepChartConfigurations
            delegate.onReorderSleepCharts(currentCharts, currentCharts)
            advanceUntilIdle()
        }
}
