package app.readylytics.health.feature.workouts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.dashboard.CardId
import app.readylytics.health.core.model.domain.sync.ForegroundSyncGateway
import app.readylytics.health.core.scoring.domain.scoring.GetWorkoutDisplayMetricsUseCase
import app.readylytics.health.core.scoring.domain.scoring.ScoringCalculator
import app.readylytics.health.domain.date.SelectedDateStore
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.preferences.UserPreferencesReader
import app.readylytics.health.domain.repository.DailySummaryRepository
import app.readylytics.health.domain.repository.HeartRateRepository
import app.readylytics.health.domain.repository.WorkoutRepository
import app.readylytics.health.domain.workouts.WorkoutChartId
import app.readylytics.health.domain.workouts.WorkoutHistoryId
import app.readylytics.health.domain.workouts.WorkoutsLayoutRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
class WorkoutsViewModelLayoutManagementTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var dailySummaryRepository: DailySummaryRepository
    private lateinit var workoutRepository: WorkoutRepository
    private lateinit var heartRateRepository: HeartRateRepository
    private lateinit var selectedDateRepository: SelectedDateStore
    private lateinit var scoringCalculator: ScoringCalculator
    private lateinit var settingsRepo: UserPreferencesReader
    private lateinit var getWorkoutDisplayMetricsUseCase: GetWorkoutDisplayMetricsUseCase
    private lateinit var foregroundSyncController: ForegroundSyncGateway
    private lateinit var workoutsLayoutRepository: WorkoutsLayoutRepository
    private lateinit var viewModel: WorkoutsViewModel

    private val selectedDateFlow = MutableStateFlow(LocalDate.now())
    private val earliestDateFlow = MutableStateFlow<LocalDate?>(null)
    private val isSyncingFlow = MutableStateFlow(false)
    private val summariesFlow = MutableStateFlow<List<DailySummary>>(emptyList())
    private val preferencesFlow = MutableStateFlow(UserPreferences())

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        dailySummaryRepository =
            mockk {
                every { observeLatest() } returns flowOf(null)
                coEvery { getByDate(any()) } returns null
                every { observeSince(any()) } returns summariesFlow
            }
        workoutRepository =
            mockk {
                coEvery { getEarliestWorkoutTimestamp() } returns null
                coEvery { countByTimeRange(any(), any()) } returns 0
                coEvery { getInRangePaged(any(), any(), any(), any()) } returns emptyList()
                coEvery { getInRange(any(), any()) } returns emptyList()
            }
        heartRateRepository = mockk { coEvery { getByTimeRange(any(), any()) } returns emptyList() }
        selectedDateRepository =
            mockk {
                every { selectedDate } returns selectedDateFlow
                every { earliestDate } returns earliestDateFlow
            }
        scoringCalculator = mockk(relaxed = true)
        settingsRepo = mockk { every { userPreferences } returns preferencesFlow }
        getWorkoutDisplayMetricsUseCase = mockk(relaxed = true)
        foregroundSyncController = mockk { every { isSyncing } returns isSyncingFlow }
        workoutsLayoutRepository =
            mockk {
                every { workoutCardConfigurations() } returns flowOf(SettingsDefaults.DEFAULT_WORKOUT_CARDS)
                every { workoutChartConfigurations() } returns flowOf(SettingsDefaults.DEFAULT_WORKOUT_CHARTS)
                every { workoutHistoryConfigurations() } returns flowOf(SettingsDefaults.DEFAULT_WORKOUT_HISTORY)
                coEvery { updateWorkoutCardConfigurations(any()) } returns Unit
                coEvery { updateWorkoutChartConfigurations(any()) } returns Unit
                coEvery { updateWorkoutHistoryConfigurations(any()) } returns Unit
            }
    }

    private fun createViewModel(): WorkoutsViewModel =
        WorkoutsViewModel(
            dailySummaryRepository = dailySummaryRepository,
            workoutRepository = workoutRepository,
            heartRateRepository = heartRateRepository,
            selectedDateRepository = selectedDateRepository,
            scoringCalculator = scoringCalculator,
            settingsRepo = settingsRepo,
            getWorkoutDisplayMetricsUseCase = getWorkoutDisplayMetricsUseCase,
            foregroundSyncController = foregroundSyncController,
            workoutsLayoutRepository = workoutsLayoutRepository,
            savedStateHandle = SavedStateHandle(),
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher,
        )

    @After
    fun tearDown() =
        runTest(testDispatcher) {
            if (::viewModel.isInitialized) {
                viewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            }
            Dispatchers.resetMain()
        }

    @Test
    fun `card management toggle enters edit mode and saving persists reordered config`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            val collector = backgroundScope.launch { viewModel.uiState.collect() }
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.isManagingCards)
            assertEquals(3, viewModel.uiState.value.cardConfigurations.size)

            viewModel.toggleWorkoutsManagement()
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isManagingCards)
            assertTrue(viewModel.uiState.value.isManagingWorkoutsLayout)

            viewModel.onToggleCardVisibility(CardId.RAS_DAILY, visible = false)
            advanceUntilIdle()
            assertFalse(
                viewModel.uiState.value.cardConfigurations
                    .first { it.cardId == CardId.RAS_DAILY }
                    .isVisible,
            )

            viewModel.toggleWorkoutsManagement()
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.isManagingCards)
            coVerify {
                workoutsLayoutRepository.updateWorkoutCardConfigurations(
                    match { configs -> configs.any { it.cardId == CardId.RAS_DAILY && !it.isVisible } },
                )
            }
            collector.cancel()
        }

    @Test
    fun `chart management toggle hides ACWR chart and persists on save`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            val collector = backgroundScope.launch { viewModel.uiState.collect() }
            advanceUntilIdle()
            assertEquals(1, viewModel.uiState.value.chartConfigurations.size)

            viewModel.toggleWorkoutsManagement()
            advanceUntilIdle()
            viewModel.onToggleChartVisibility(WorkoutChartId.ACWR_TRIMP, visible = false)
            advanceUntilIdle()
            assertFalse(
                viewModel.uiState.value.chartConfigurations
                    .first { it.chartId == WorkoutChartId.ACWR_TRIMP }
                    .isVisible,
            )

            viewModel.toggleWorkoutsManagement()
            advanceUntilIdle()
            coVerify {
                workoutsLayoutRepository.updateWorkoutChartConfigurations(
                    match { charts -> charts.any { it.chartId == WorkoutChartId.ACWR_TRIMP && !it.isVisible } },
                )
            }
            collector.cancel()
        }

    @Test
    fun `history management toggle hides workout list and persists on save`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            val collector = backgroundScope.launch { viewModel.uiState.collect() }
            advanceUntilIdle()
            assertEquals(1, viewModel.uiState.value.historyConfigurations.size)

            viewModel.toggleWorkoutsManagement()
            advanceUntilIdle()
            viewModel.onToggleHistoryVisibility(WorkoutHistoryId.WORKOUT_LIST, visible = false)
            advanceUntilIdle()
            assertFalse(
                viewModel.uiState.value.historyConfigurations
                    .first { it.historyId == WorkoutHistoryId.WORKOUT_LIST }
                    .isVisible,
            )

            viewModel.toggleWorkoutsManagement()
            advanceUntilIdle()
            coVerify {
                workoutsLayoutRepository.updateWorkoutHistoryConfigurations(
                    match { history ->
                        history.any { it.historyId == WorkoutHistoryId.WORKOUT_LIST && !it.isVisible }
                    },
                )
            }
            collector.cancel()
        }

    @Test
    fun `cancel workouts management discards changes without persisting`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            val collector = backgroundScope.launch { viewModel.uiState.collect() }
            advanceUntilIdle()
            viewModel.toggleWorkoutsManagement()
            advanceUntilIdle()
            viewModel.onToggleCardVisibility(CardId.RAS_DAILY, visible = false)
            advanceUntilIdle()

            viewModel.onCancelWorkoutsManagement()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isManagingWorkoutsLayout)
            assertTrue(
                viewModel.uiState.value.cardConfigurations
                    .all { it.isVisible },
            )
            coVerify(exactly = 0) { workoutsLayoutRepository.updateWorkoutCardConfigurations(any()) }
            collector.cancel()
        }

    @Test
    fun `reset workouts to defaults restores default configurations and persists on save`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            val collector = backgroundScope.launch { viewModel.uiState.collect() }
            advanceUntilIdle()
            viewModel.toggleWorkoutsManagement()
            advanceUntilIdle()

            viewModel.onResetWorkoutsToDefaults()
            advanceUntilIdle()

            assertEquals(SettingsDefaults.DEFAULT_WORKOUT_CARDS, viewModel.uiState.value.cardConfigurations)
            assertEquals(SettingsDefaults.DEFAULT_WORKOUT_CHARTS, viewModel.uiState.value.chartConfigurations)
            assertEquals(SettingsDefaults.DEFAULT_WORKOUT_HISTORY, viewModel.uiState.value.historyConfigurations)

            viewModel.toggleWorkoutsManagement()
            advanceUntilIdle()
            coVerify {
                workoutsLayoutRepository.updateWorkoutCardConfigurations(
                    SettingsDefaults.DEFAULT_WORKOUT_CARDS,
                )
            }
            collector.cancel()
        }
}
