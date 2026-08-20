package app.readylytics.health.feature.vitals.overview

import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import app.readylytics.health.core.model.domain.dashboard.CardId
import app.readylytics.health.core.model.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.domain.vitals.VitalsChartId
import io.mockk.coVerify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VitalsViewModelLayoutManagementTest : VitalsViewModelTestBase() {
    @Test
    fun `vitals card management toggle enters edit mode and saving persists reordered config`() =
        runTest {
            viewModel = createViewModel()
            val collector = backgroundScope.launch { viewModel.uiState.collect() }
            try {
                advanceUntilIdle()
                assertFalse(viewModel.uiState.value.isManagingVitalsCards)
                assertEquals(2, viewModel.uiState.value.vitalsCardConfigurations.size)

                viewModel.toggleVitalsCardManagement()
                advanceUntilIdle()
                assertTrue(viewModel.uiState.value.isManagingVitalsCards)
                assertTrue(viewModel.uiState.value.isManagingVitalsLayout)

                viewModel.onToggleVitalsCardVisibility(CardId.HRV, visible = false)
                advanceUntilIdle()
                assertEquals(
                    false,
                    viewModel.uiState.value.vitalsCardConfigurations
                        .first { it.cardId == CardId.HRV }
                        .isVisible,
                )

                viewModel.toggleVitalsCardManagement()
                advanceUntilIdle()
                assertFalse(viewModel.uiState.value.isManagingVitalsCards)
                assertFalse(viewModel.uiState.value.isManagingVitalsLayout)
                coVerify {
                    vitalsLayoutRepository.updateVitalsCardConfigurations(
                        match { configs ->
                            configs.any { it.cardId == CardId.HRV && !it.isVisible }
                        },
                    )
                }
            } finally {
                collector.cancel()
            }
        }

    @Test
    fun `vitals chart management toggle hides a chart and persists on save`() =
        runTest {
            viewModel = createViewModel()
            val collector = backgroundScope.launch { viewModel.uiState.collect() }
            try {
                advanceUntilIdle()
                assertFalse(viewModel.uiState.value.isManagingVitalsCharts)
                assertEquals(4, viewModel.uiState.value.vitalsChartConfigurations.size)

                viewModel.toggleVitalsChartManagement()
                advanceUntilIdle()
                assertTrue(viewModel.uiState.value.isManagingVitalsCharts)
                assertTrue(viewModel.uiState.value.isManagingVitalsLayout)

                viewModel.onToggleVitalsChartVisibility(VitalsChartId.HRV_TREND, visible = false)
                advanceUntilIdle()
                assertEquals(
                    false,
                    viewModel.uiState.value.vitalsChartConfigurations
                        .first { it.chartId == VitalsChartId.HRV_TREND }
                        .isVisible,
                )

                viewModel.toggleVitalsChartManagement()
                advanceUntilIdle()
                assertFalse(viewModel.uiState.value.isManagingVitalsCharts)
                assertFalse(viewModel.uiState.value.isManagingVitalsLayout)
                coVerify {
                    vitalsLayoutRepository.updateVitalsChartConfigurations(
                        match { charts ->
                            charts.any { it.chartId == VitalsChartId.HRV_TREND && !it.isVisible }
                        },
                    )
                }
            } finally {
                collector.cancel()
            }
        }

    @Test
    fun `cancel vitals card management discards changes without persisting`() =
        runTest {
            viewModel = createViewModel()
            val collector = backgroundScope.launch { viewModel.uiState.collect() }
            try {
                advanceUntilIdle()
                viewModel.toggleVitalsCardManagement()
                advanceUntilIdle()
                assertTrue(viewModel.uiState.value.isManagingVitalsLayout)

                viewModel.onToggleVitalsCardVisibility(CardId.HRV, visible = false)
                advanceUntilIdle()
                viewModel.onCancelVitalsCardManagement()
                advanceUntilIdle()

                assertFalse(viewModel.uiState.value.isManagingVitalsCards)
                assertFalse(viewModel.uiState.value.isManagingVitalsLayout)
                assertTrue(
                    "cancel must not leak pending edits into the committed set",
                    viewModel.uiState.value.vitalsCardConfigurations
                        .all { it.isVisible },
                )
                coVerify(exactly = 0) {
                    vitalsLayoutRepository.updateVitalsCardConfigurations(any())
                }
            } finally {
                collector.cancel()
            }
        }

    @Test
    fun `cancel vitals chart management discards changes without persisting`() =
        runTest {
            viewModel = createViewModel()
            val collector = backgroundScope.launch { viewModel.uiState.collect() }
            try {
                advanceUntilIdle()
                viewModel.toggleVitalsChartManagement()
                advanceUntilIdle()
                assertTrue(viewModel.uiState.value.isManagingVitalsLayout)

                viewModel.onToggleVitalsChartVisibility(VitalsChartId.HRV_TREND, visible = false)
                advanceUntilIdle()
                viewModel.onCancelVitalsChartManagement()
                advanceUntilIdle()

                assertFalse(viewModel.uiState.value.isManagingVitalsCharts)
                assertFalse(viewModel.uiState.value.isManagingVitalsLayout)
                assertTrue(
                    "cancel must not leak pending edits into the committed set",
                    viewModel.uiState.value.vitalsChartConfigurations
                        .all { it.isVisible },
                )
                coVerify(exactly = 0) {
                    vitalsLayoutRepository.updateVitalsChartConfigurations(any())
                }
            } finally {
                collector.cancel()
            }
        }

    @Test
    fun `reset vitals to defaults restores default card and chart configurations and persists on save`() =
        runTest {
            viewModel = createViewModel()
            val collector = backgroundScope.launch { viewModel.uiState.collect() }
            try {
                advanceUntilIdle()
                viewModel.toggleVitalsCardManagement()
                viewModel.toggleVitalsChartManagement()
                advanceUntilIdle()

                viewModel.onResetVitalsToDefaults()
                advanceUntilIdle()

                assertEquals(
                    SettingsDefaults.DEFAULT_VITALS_CARDS,
                    viewModel.uiState.value.vitalsCardConfigurations,
                )
                assertEquals(
                    SettingsDefaults.DEFAULT_VITALS_CHARTS,
                    viewModel.uiState.value.vitalsChartConfigurations,
                )

                viewModel.toggleVitalsCardManagement()
                viewModel.toggleVitalsChartManagement()
                advanceUntilIdle()

                coVerify {
                    vitalsLayoutRepository.updateVitalsCardConfigurations(
                        SettingsDefaults.DEFAULT_VITALS_CARDS,
                    )
                }
                coVerify {
                    vitalsLayoutRepository.updateVitalsChartConfigurations(
                        SettingsDefaults.DEFAULT_VITALS_CHARTS,
                    )
                }
            } finally {
                collector.cancel()
            }
        }

    @Test
    fun `reorder vitals cards updates pending order and persists on save`() =
        runTest {
            viewModel = createViewModel()
            val collector = backgroundScope.launch { viewModel.uiState.collect() }
            try {
                advanceUntilIdle()
                viewModel.toggleVitalsCardManagement()
                advanceUntilIdle()
                assertTrue(viewModel.uiState.value.isManagingVitalsLayout)

                val newOrder =
                    listOf(
                        viewModel.uiState.value.vitalsCardConfigurations[1],
                        viewModel.uiState.value.vitalsCardConfigurations[0],
                    )
                viewModel.onReorderVitalsCards(newOrder)
                advanceUntilIdle()

                val reordered = viewModel.uiState.value.vitalsCardConfigurations
                assertEquals(listOf(CardId.HRV, CardId.RESTING_HR), reordered.map { it.cardId })
                assertEquals(listOf(0, 1), reordered.map { it.position })

                viewModel.toggleVitalsCardManagement()
                advanceUntilIdle()
                assertFalse(viewModel.uiState.value.isManagingVitalsLayout)
                coVerify {
                    vitalsLayoutRepository.updateVitalsCardConfigurations(
                        match { configs -> configs.first().cardId == CardId.HRV },
                    )
                }
            } finally {
                collector.cancel()
            }
        }

    @Test
    fun `vitals card display mode change updates pending config and persists on save`() =
        runTest {
            viewModel = createViewModel()
            val collector = backgroundScope.launch { viewModel.uiState.collect() }
            try {
                advanceUntilIdle()
                viewModel.toggleVitalsCardManagement()
                advanceUntilIdle()
                assertTrue(viewModel.uiState.value.isManagingVitalsLayout)

                viewModel.onVitalsCardDisplayModeChanged(CardId.HRV, DashboardCardDisplayMode.BAR)
                advanceUntilIdle()
                assertEquals(
                    DashboardCardDisplayMode.BAR,
                    viewModel.uiState.value.vitalsCardConfigurations
                        .first { it.cardId == CardId.HRV }
                        .requestedDisplayMode,
                )

                viewModel.toggleVitalsCardManagement()
                advanceUntilIdle()
                assertFalse(viewModel.uiState.value.isManagingVitalsLayout)
                coVerify {
                    vitalsLayoutRepository.updateVitalsCardConfigurations(
                        match { configs ->
                            configs.any {
                                it.cardId == CardId.HRV &&
                                    it.requestedDisplayMode == DashboardCardDisplayMode.BAR
                            }
                        },
                    )
                }
            } finally {
                collector.cancel()
            }
        }

    @Test
    fun `reorder vitals charts updates pending order and persists on save`() =
        runTest {
            viewModel = createViewModel()
            val collector = backgroundScope.launch { viewModel.uiState.collect() }
            try {
                advanceUntilIdle()
                viewModel.toggleVitalsChartManagement()
                advanceUntilIdle()
                assertTrue(viewModel.uiState.value.isManagingVitalsLayout)

                val current = viewModel.uiState.value.vitalsChartConfigurations
                val newOrder =
                    listOf(
                        current.first { it.chartId == VitalsChartId.BODY_TEMP_TREND },
                        current.first { it.chartId == VitalsChartId.HRV_TREND },
                        current.first { it.chartId == VitalsChartId.RHR_TREND },
                        current.first { it.chartId == VitalsChartId.SPO2_TREND },
                    )
                viewModel.onReorderVitalsCharts(newOrder)
                advanceUntilIdle()

                val reordered = viewModel.uiState.value.vitalsChartConfigurations
                assertEquals(
                    listOf(
                        VitalsChartId.BODY_TEMP_TREND,
                        VitalsChartId.HRV_TREND,
                        VitalsChartId.RHR_TREND,
                        VitalsChartId.SPO2_TREND,
                    ),
                    reordered.map { it.chartId },
                )
                assertEquals(listOf(0, 1, 2, 3), reordered.map { it.position })

                viewModel.toggleVitalsChartManagement()
                advanceUntilIdle()
                assertFalse(viewModel.uiState.value.isManagingVitalsLayout)
                coVerify {
                    vitalsLayoutRepository.updateVitalsChartConfigurations(
                        match { charts -> charts.first().chartId == VitalsChartId.BODY_TEMP_TREND },
                    )
                }
            } finally {
                collector.cancel()
            }
        }
}
