package app.readylytics.health.ui.workouts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.data.repository.SelectedDateRepository
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.repository.DailySummaryRepository
import app.readylytics.health.domain.repository.HeartRateRepository
import app.readylytics.health.domain.repository.WorkoutData
import app.readylytics.health.domain.repository.WorkoutRepository
import app.readylytics.health.domain.scoring.GetWorkoutDisplayMetricsUseCase
import app.readylytics.health.domain.scoring.ScoringCalculator
import app.readylytics.health.domain.scoring.WorkoutDisplayMetrics
import app.readylytics.health.domain.sync.ForegroundSyncController
import app.readylytics.health.core.ui.common.TimeRange
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var dailySummaryRepository: DailySummaryRepository
    private lateinit var workoutRepository: WorkoutRepository
    private lateinit var heartRateRepository: HeartRateRepository
    private lateinit var selectedDateRepository: SelectedDateRepository
    private lateinit var scoringCalculator: ScoringCalculator
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var getWorkoutDisplayMetricsUseCase: GetWorkoutDisplayMetricsUseCase
    private lateinit var foregroundSyncController: ForegroundSyncController
    private lateinit var savedStateHandle: SavedStateHandle

    private lateinit var viewModel: WorkoutsViewModel

    private val selectedDateFlow = MutableStateFlow(LocalDate.now())
    private val earliestDateFlow = MutableStateFlow<LocalDate?>(null)
    private val isSyncingFlow = MutableStateFlow(false)
    private val workoutsFlow = MutableStateFlow<List<WorkoutData>>(emptyList())
    private val summariesFlow = MutableStateFlow<List<DailySummary>>(emptyList())

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
                every { observeSince(any()) } returns workoutsFlow
            }
        heartRateRepository =
            mockk {
                coEvery { getByTimeRange(any(), any()) } returns emptyList()
            }

        selectedDateRepository =
            mockk {
                every { selectedDate } returns selectedDateFlow
                every { earliestDate } returns earliestDateFlow
                coEvery { updateSelectedDate(any()) } answers {
                    selectedDateFlow.value = firstArg()
                }
                coEvery { selectPreviousDay() } answers {
                    selectedDateFlow.value = selectedDateFlow.value.minusDays(1)
                }
                coEvery { selectNextDay() } answers {
                    selectedDateFlow.value = selectedDateFlow.value.plusDays(1)
                }
            }

        scoringCalculator = mockk(relaxed = true)
        settingsRepo =
            mockk {
                every { userPreferences } returns MutableStateFlow(UserPreferences())
            }
        getWorkoutDisplayMetricsUseCase =
            mockk(relaxed = true) {
                coEvery {
                    execute(
                        workout = any(),
                        samples = any(),
                    )
                } returns
                    WorkoutDisplayMetrics(
                        preciseTrimp = 50f,
                        computedTrimp = 50,
                        trimpDisplay = "50",
                        gainedStrain = 0.36f,
                        gainedStrainDisplay = "0.36",
                    )
            }
        foregroundSyncController =
            mockk {
                every { isSyncing } returns isSyncingFlow
            }
        savedStateHandle = SavedStateHandle()
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
            savedStateHandle = savedStateHandle,
            ioDispatcher = testDispatcher,
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
    fun `initial page is 1`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            testScheduler.advanceUntilIdle()
            assertEquals(1, viewModel.currentPage.value)
            collectJob.cancel()
        }

    @Test
    fun `clamping behaves correctly when pages are updated`() =
        runTest(testDispatcher) {
            // Mock 25 workouts to create 3 pages (10 per page)
            val dummyWorkouts =
                (1..25).map { id ->
                    WorkoutData(
                        id = id.toString(),
                        startTime = System.currentTimeMillis() - (id * 1000 * 60),
                        endTime = System.currentTimeMillis(),
                        exerciseType = "running",
                        durationMinutes = 30,
                        zone1Minutes = 0f,
                        zone2Minutes = 0f,
                        zone3Minutes = 0f,
                        zone4Minutes = 0f,
                        zone5Minutes = 0f,
                        trimp = 50f,
                        avgHr = 130f,
                    )
                }
            workoutsFlow.value = dummyWorkouts

            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            testScheduler.advanceUntilIdle()

            // Wait for flow to emit the workouts list
            val state = viewModel.uiState.first { it.recentWorkouts.isNotEmpty() }
            assertEquals(3, state.totalPages)
            assertEquals(1, state.currentPage)
            assertEquals(10, state.recentWorkouts.size)

            // Go to next page
            viewModel.onNextPage()
            testScheduler.advanceUntilIdle()
            assertEquals(2, viewModel.currentPage.value)

            // Go to page 3
            viewModel.onNextPage()
            testScheduler.advanceUntilIdle()
            assertEquals(3, viewModel.currentPage.value)

            // Try to go past max page
            viewModel.onNextPage()
            testScheduler.advanceUntilIdle()
            assertEquals(3, viewModel.currentPage.value)

            // Go to previous page
            viewModel.onPreviousPage()
            testScheduler.advanceUntilIdle()
            assertEquals(2, viewModel.currentPage.value)

            collectJob.cancel()
        }

    @Test
    fun `page resets to 1 when range changes`() =
        runTest(testDispatcher) {
            val dummyWorkouts =
                (1..25).map { id ->
                    WorkoutData(
                        id = id.toString(),
                        startTime = System.currentTimeMillis() - (id * 1000 * 60),
                        endTime = System.currentTimeMillis(),
                        exerciseType = "running",
                        durationMinutes = 30,
                        zone1Minutes = 0f,
                        zone2Minutes = 0f,
                        zone3Minutes = 0f,
                        zone4Minutes = 0f,
                        zone5Minutes = 0f,
                        trimp = 50f,
                        avgHr = 130f,
                    )
                }
            workoutsFlow.value = dummyWorkouts

            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            testScheduler.advanceUntilIdle()

            // Wait for first emission to load workouts and compute totalPages
            viewModel.uiState.first { it.recentWorkouts.isNotEmpty() }

            // Move to page 2
            viewModel.onNextPage()
            testScheduler.advanceUntilIdle()
            assertEquals(2, viewModel.currentPage.value)

            // Change range
            viewModel.onRangeSelected(TimeRange.THIRTY_DAYS)
            testScheduler.advanceUntilIdle()
            assertEquals(1, viewModel.currentPage.value)

            collectJob.cancel()
        }

    @Test
    fun `page resets to 1 when date changes`() =
        runTest(testDispatcher) {
            val dummyWorkouts =
                (1..25).map { id ->
                    WorkoutData(
                        id = id.toString(),
                        startTime = System.currentTimeMillis() - (id * 1000 * 60),
                        endTime = System.currentTimeMillis(),
                        exerciseType = "running",
                        durationMinutes = 30,
                        zone1Minutes = 0f,
                        zone2Minutes = 0f,
                        zone3Minutes = 0f,
                        zone4Minutes = 0f,
                        zone5Minutes = 0f,
                        trimp = 50f,
                        avgHr = 130f,
                    )
                }
            workoutsFlow.value = dummyWorkouts

            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            testScheduler.advanceUntilIdle()

            // Wait for first emission to load workouts and compute totalPages
            viewModel.uiState.first { it.recentWorkouts.isNotEmpty() }

            // Move to page 2
            viewModel.onNextPage()
            testScheduler.advanceUntilIdle()
            assertEquals(2, viewModel.currentPage.value)

            // Change date
            viewModel.onDateSelected(LocalDate.now().minusDays(1))
            testScheduler.advanceUntilIdle()
            assertEquals(1, viewModel.currentPage.value)

            collectJob.cancel()
        }

    @Test
    fun `recent workout uses rounded load metrics from shared use case`() =
        runTest(testDispatcher) {
            val today = LocalDate.now()
            val startMs =
                today
                    .atStartOfDay(java.time.ZoneId.systemDefault())
                    .plusHours(8)
                    .toInstant()
                    .toEpochMilli()
            val workout =
                WorkoutData(
                    id = "run-1",
                    startTime = startMs,
                    endTime = startMs + 60 * 60 * 1000L,
                    exerciseType = "running",
                    durationMinutes = 60,
                    zone1Minutes = 0f,
                    zone2Minutes = 10f,
                    zone3Minutes = 20f,
                    zone4Minutes = 30f,
                    zone5Minutes = 0f,
                    trimp = 115.6f,
                    avgHr = 134f,
                )
            workoutsFlow.value = listOf(workout)
            summariesFlow.value = listOf(DailySummary(date = today, trimpWorkoutOnly = 115.6f, rhrBpm = 52f))
            coEvery {
                getWorkoutDisplayMetricsUseCase.execute(
                    workout = workout,
                    samples = emptyList(),
                )
            } returns
                WorkoutDisplayMetrics(
                    preciseTrimp = 115.6f,
                    computedTrimp = 116,
                    trimpDisplay = "116",
                    gainedStrain = 0.37f,
                    gainedStrainDisplay = "0.37",
                )

            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            val state = viewModel.uiState.first { it.recentWorkouts.isNotEmpty() }

            assertEquals(0.37f, state.recentWorkouts.single().gainedStrain)
            assertEquals("0.37", state.recentWorkouts.single().gainedStrainDisplay)
            assertEquals(116, state.recentWorkouts.single().computedTrimp)

            collectJob.cancelAndJoin()
        }

    @Test
    fun `stats state exposes canonical latest daily metrics`() =
        runTest(testDispatcher) {
            val today = LocalDate.now()
            summariesFlow.value =
                listOf(
                    DailySummary(
                        date = today,
                        // Default strainLoadSourceMode is WORKOUT_ONLY.
                        readinessWorkoutOnly = 72.5f,
                        strainRatioWorkoutOnly = 0.365f,
                    ),
                )
            every { dailySummaryRepository.observeLatest() } returns flowOf(summariesFlow.value.single())

            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            val state = viewModel.uiState.first { it.latestMetrics != null }

            assertEquals(73, state.latestMetrics?.readinessRounded)
            assertEquals("0.37", state.latestMetrics?.strainRatioDisplay)

            collectJob.cancelAndJoin()
        }

    @Test
    fun `stats state computes todayStrainIncrease correctly`() =
        runTest(testDispatcher) {
            val today = LocalDate.now()
            summariesFlow.value =
                listOf(
                    DailySummary(
                        date = today,
                        readinessWorkoutOnly = 72.5f,
                        strainRatioWorkoutOnly = 0.365f,
                        trimpWorkoutOnly = 15f,
                    ),
                )
            every { dailySummaryRepository.observeLatest() } returns flowOf(summariesFlow.value.single())
            coEvery { workoutRepository.getEarliestWorkoutTimestamp() } returns
                today
                    .minusDays(10)
                    .atStartOfDay(java.time.ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()

            every { scoringCalculator.computeCtlEmaSeries(any(), any(), any()) } returns mapOf(today to 10f)
            every { scoringCalculator.computeAtlEmaSeries(any(), any(), any()) } returns mapOf(today to 15f)
            every { scoringCalculator.computeStrainRatio(15f, 10f) } returns 1.5f
            every { scoringCalculator.computeStrainRatio(12f, 10f) } returns 1.2f

            // Distinguish atlWith and atlWithout calls
            every { scoringCalculator.computeAtlEmaWithDecay(match { it[today] == 0f }, today) } returns 12f
            every { scoringCalculator.computeCtlEmaWithDecay(match { it[today] == 0f }, today) } returns 10f

            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            val state = viewModel.uiState.first { it.todayStrainIncrease != null }

            // 1.5f - 1.2f = 0.3f
            assertEquals(0.3f, state.todayStrainIncrease!!, 0.001f)

            collectJob.cancelAndJoin()
        }
}
