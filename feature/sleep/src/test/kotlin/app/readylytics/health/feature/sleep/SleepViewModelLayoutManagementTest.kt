package app.readylytics.health.feature.sleep

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.core.model.domain.date.SelectedDateStore
import app.readylytics.health.core.model.domain.preferences.UserPreferencesReader
import app.readylytics.health.core.model.domain.sync.ForegroundSyncGateway
import app.readylytics.health.core.scoring.domain.scoring.CircadianConsistencyRepository
import app.readylytics.health.core.scoring.domain.scoring.CircadianConsistencyResult
import app.readylytics.health.domain.repository.DailyMetricsRepository
import app.readylytics.health.domain.repository.DailySummaryRepository
import app.readylytics.health.domain.repository.HeartRateRepository
import app.readylytics.health.domain.repository.SleepSessionRepository
import app.readylytics.health.domain.sleep.SleepChartId
import app.readylytics.health.domain.sleep.SleepLayoutRepository
import app.readylytics.health.domain.sleep.SleepMetricCardId
import app.readylytics.health.domain.sleep.SleepTopCardId
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
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
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class SleepViewModelLayoutManagementTest {
    private val testDispatcher = StandardTestDispatcher()

    private val dailySummaryRepository: DailySummaryRepository = mockk(relaxed = true)
    private val dailyMetricsRepository: DailyMetricsRepository = mockk(relaxed = true)
    private val sleepSessionRepository: SleepSessionRepository = mockk(relaxed = true)
    private val heartRateRepository: HeartRateRepository = mockk(relaxed = true)
    private val settingsRepo: UserPreferencesReader = mockk(relaxed = true)
    private val selectedDateRepository: SelectedDateStore = mockk(relaxed = true)
    private val circadianRepo: CircadianConsistencyRepository = mockk(relaxed = true)
    private val foregroundSyncController: ForegroundSyncGateway = mockk(relaxed = true)
    private val savedStateHandle: SavedStateHandle = mockk(relaxed = true)
    private val sleepLayoutRepository: SleepLayoutRepository = mockk(relaxed = true)

    private val selectedDateFlow = MutableStateFlow(LocalDate.of(2026, 6, 11))
    private val topCardsFlow = MutableStateFlow(SettingsDefaults.DEFAULT_SLEEP_TOP_CARDS)
    private val chartsFlow = MutableStateFlow(SettingsDefaults.DEFAULT_SLEEP_CHARTS)
    private val metricCardsFlow = MutableStateFlow(SettingsDefaults.DEFAULT_SLEEP_METRIC_CARDS)
    private lateinit var viewModel: SleepViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        every { selectedDateRepository.selectedDate } returns selectedDateFlow
        every { selectedDateRepository.earliestDate } returns MutableStateFlow(null)
        every { circadianRepo.resultFor(any()) } returns flowOf(CircadianConsistencyResult.Calibrating)
        every { foregroundSyncController.isSyncing } returns MutableStateFlow(false)
        every { dailyMetricsRepository.observeByDate(any()) } returns MutableStateFlow(null)
        every { settingsRepo.userPreferences } returns flowOf(UserPreferences(goalSleepHours = 8f))
        every { dailySummaryRepository.observeSince(any()) } returns flowOf(emptyList())
        coEvery { dailySummaryRepository.getByDate(any()) } returns null
        every { dailySummaryRepository.observeByDate(any()) } returns MutableStateFlow(null)
        every { sleepSessionRepository.observeSince(any()) } returns flowOf(emptyList())
        every { sleepSessionRepository.observeFirstSessionEndingInRange(any(), any()) } returns flowOf(null)

        every { sleepLayoutRepository.sleepTopCardConfigurations() } returns topCardsFlow
        every { sleepLayoutRepository.sleepChartConfigurations() } returns chartsFlow
        every { sleepLayoutRepository.sleepMetricCardConfigurations() } returns metricCardsFlow
    }

    @After
    fun tearDown() =
        runTest(testDispatcher) {
            if (::viewModel.isInitialized) {
                viewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            }
            Dispatchers.resetMain()
        }

    private fun createViewModel() =
        SleepViewModel(
            dailySummaryRepository = dailySummaryRepository,
            dailyMetricsRepository = dailyMetricsRepository,
            sleepSessionRepository = sleepSessionRepository,
            heartRateRepository = heartRateRepository,
            settingsRepo = settingsRepo,
            selectedDateRepository = selectedDateRepository,
            circadianRepo = circadianRepo,
            foregroundSyncController = foregroundSyncController,
            savedStateHandle = savedStateHandle,
            sleepLayoutRepository = sleepLayoutRepository,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher,
        )

    @Test
    fun `sleep layout management toggle enters edit mode and saving persists updated configs`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            val collector = backgroundScope.launch { viewModel.uiState.collect() }
            try {
                advanceUntilIdle()
                assertFalse(viewModel.uiState.value.isManagingSleepLayout)
                assertEquals(5, viewModel.uiState.value.sleepTopCardConfigurations.size)

                viewModel.toggleSleepLayoutManagement()
                advanceUntilIdle()
                assertTrue(viewModel.uiState.value.isManagingSleepLayout)

                viewModel.onToggleSleepTopCardVisibility(SleepTopCardId.SLEEP_SCORE, visible = false)
                advanceUntilIdle()
                assertEquals(
                    false,
                    viewModel.uiState.value.sleepTopCardConfigurations
                        .first { it.cardId == SleepTopCardId.SLEEP_SCORE }
                        .isVisible,
                )

                viewModel.toggleSleepLayoutManagement()
                advanceUntilIdle()
                assertFalse(viewModel.uiState.value.isManagingSleepLayout)
                coVerify {
                    sleepLayoutRepository.updateSleepTopCardConfigurations(
                        match { configs ->
                            configs.any { it.cardId == SleepTopCardId.SLEEP_SCORE && !it.isVisible }
                        },
                    )
                }
            } finally {
                collector.cancel()
            }
        }

    @Test
    fun `sleep chart management toggle hides a chart and persists on save`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            val collector = backgroundScope.launch { viewModel.uiState.collect() }
            try {
                advanceUntilIdle()
                assertFalse(viewModel.uiState.value.isManagingSleepCharts)
                assertEquals(1, viewModel.uiState.value.sleepChartConfigurations.size)

                viewModel.toggleSleepLayoutManagement()
                advanceUntilIdle()
                assertTrue(viewModel.uiState.value.isManagingSleepCharts)

                viewModel.onToggleSleepChartVisibility(SleepChartId.SLEEP_DURATION_TREND, visible = false)
                advanceUntilIdle()
                assertEquals(
                    false,
                    viewModel.uiState.value.sleepChartConfigurations
                        .first { it.chartId == SleepChartId.SLEEP_DURATION_TREND }
                        .isVisible,
                )

                viewModel.toggleSleepLayoutManagement()
                advanceUntilIdle()
                assertFalse(viewModel.uiState.value.isManagingSleepCharts)
                coVerify {
                    sleepLayoutRepository.updateSleepChartConfigurations(
                        match { charts ->
                            charts.any { it.chartId == SleepChartId.SLEEP_DURATION_TREND && !it.isVisible }
                        },
                    )
                }
            } finally {
                collector.cancel()
            }
        }

    @Test
    fun `cancel sleep layout management discards changes without persisting`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            val collector = backgroundScope.launch { viewModel.uiState.collect() }
            try {
                advanceUntilIdle()
                viewModel.toggleSleepLayoutManagement()
                advanceUntilIdle()
                assertTrue(viewModel.uiState.value.isManagingSleepLayout)

                viewModel.onToggleSleepTopCardVisibility(SleepTopCardId.SLEEP_SCORE, visible = false)
                viewModel.onToggleSleepMetricCardVisibility(SleepMetricCardId.CIRCADIAN_CONSISTENCY, visible = false)
                advanceUntilIdle()

                viewModel.onCancelSleepLayoutManagement()
                advanceUntilIdle()

                assertFalse(viewModel.uiState.value.isManagingSleepLayout)
                assertTrue(
                    "cancel must not leak pending edits into the committed top cards",
                    viewModel.uiState.value.sleepTopCardConfigurations
                        .all { it.isVisible },
                )
                assertTrue(
                    "cancel must not leak pending edits into the committed metric cards",
                    viewModel.uiState.value.sleepMetricCardConfigurations
                        .all { it.isVisible },
                )
                coVerify(exactly = 0) {
                    sleepLayoutRepository.updateSleepTopCardConfigurations(any())
                }
                coVerify(exactly = 0) {
                    sleepLayoutRepository.updateSleepMetricCardConfigurations(any())
                }
            } finally {
                collector.cancel()
            }
        }

    @Test
    fun `reset sleep layout to defaults restores default configurations and persists on save`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            val collector = backgroundScope.launch { viewModel.uiState.collect() }
            try {
                advanceUntilIdle()
                viewModel.toggleSleepLayoutManagement()
                advanceUntilIdle()

                viewModel.onResetSleepLayoutToDefaults()
                advanceUntilIdle()

                assertEquals(
                    SettingsDefaults.DEFAULT_SLEEP_TOP_CARDS,
                    viewModel.uiState.value.sleepTopCardConfigurations,
                )
                assertEquals(
                    SettingsDefaults.DEFAULT_SLEEP_CHARTS,
                    viewModel.uiState.value.sleepChartConfigurations,
                )
                assertEquals(
                    SettingsDefaults.DEFAULT_SLEEP_METRIC_CARDS,
                    viewModel.uiState.value.sleepMetricCardConfigurations,
                )

                viewModel.toggleSleepLayoutManagement()
                advanceUntilIdle()

                coVerify {
                    sleepLayoutRepository.updateSleepTopCardConfigurations(
                        SettingsDefaults.DEFAULT_SLEEP_TOP_CARDS,
                    )
                }
                coVerify {
                    sleepLayoutRepository.updateSleepChartConfigurations(
                        SettingsDefaults.DEFAULT_SLEEP_CHARTS,
                    )
                }
                coVerify {
                    sleepLayoutRepository.updateSleepMetricCardConfigurations(
                        SettingsDefaults.DEFAULT_SLEEP_METRIC_CARDS,
                    )
                }
            } finally {
                collector.cancel()
            }
        }

    @Test
    fun `reorder sleep metric cards updates pending order and persists on save`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            val collector = backgroundScope.launch { viewModel.uiState.collect() }
            try {
                advanceUntilIdle()
                viewModel.toggleSleepLayoutManagement()
                advanceUntilIdle()

                val current = viewModel.uiState.value.sleepMetricCardConfigurations
                val newOrder =
                    listOf(
                        current[1],
                        current[0],
                    ) + current.drop(2)
                viewModel.onReorderSleepMetricCards(newOrder)
                advanceUntilIdle()

                val reordered = viewModel.uiState.value.sleepMetricCardConfigurations
                assertEquals(SleepMetricCardId.SLEEP_EFFICIENCY, reordered[0].cardId)
                assertEquals(SleepMetricCardId.CIRCADIAN_CONSISTENCY, reordered[1].cardId)

                viewModel.toggleSleepLayoutManagement()
                advanceUntilIdle()

                coVerify {
                    sleepLayoutRepository.updateSleepMetricCardConfigurations(
                        match { configs -> configs.first().cardId == SleepMetricCardId.SLEEP_EFFICIENCY },
                    )
                }
            } finally {
                collector.cancel()
            }
        }

    @Test
    fun `sleep metric card display mode change updates pending config and persists on save`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            val collector = backgroundScope.launch { viewModel.uiState.collect() }
            try {
                advanceUntilIdle()
                viewModel.toggleSleepLayoutManagement()
                advanceUntilIdle()

                viewModel.onSleepMetricCardDisplayModeChanged(
                    SleepMetricCardId.SLEEP_EFFICIENCY,
                    DashboardCardDisplayMode.BAR,
                )
                advanceUntilIdle()
                assertEquals(
                    DashboardCardDisplayMode.BAR,
                    viewModel.uiState.value.sleepMetricCardConfigurations
                        .first { it.cardId == SleepMetricCardId.SLEEP_EFFICIENCY }
                        .requestedDisplayMode,
                )

                viewModel.toggleSleepLayoutManagement()
                advanceUntilIdle()

                coVerify {
                    sleepLayoutRepository.updateSleepMetricCardConfigurations(
                        match { configs ->
                            configs.any {
                                it.cardId == SleepMetricCardId.SLEEP_EFFICIENCY &&
                                    it.requestedDisplayMode == DashboardCardDisplayMode.BAR
                            }
                        },
                    )
                }
            } finally {
                collector.cancel()
            }
        }
}
