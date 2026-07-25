package app.readylytics.health.feature.workouts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.readylytics.health.core.ui.common.TimeRange
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.domain.date.SelectedDateStore
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.preferences.UserPreferencesReader
import app.readylytics.health.domain.repository.DailySummaryRepository
import app.readylytics.health.domain.repository.HeartRateRepository
import app.readylytics.health.domain.repository.WorkoutData
import app.readylytics.health.domain.repository.WorkoutRepository
import app.readylytics.health.domain.scoring.GetWorkoutDisplayMetricsUseCase
import app.readylytics.health.domain.scoring.LoadSourceMode
import app.readylytics.health.domain.scoring.ScoringCalculator
import app.readylytics.health.domain.scoring.WorkoutDisplayMetrics
import app.readylytics.health.domain.scoring.WorkoutIntensityLevel
import app.readylytics.health.domain.scoring.WorkoutLoadClassification
import app.readylytics.health.domain.scoring.WorkoutLoadLevel
import app.readylytics.health.domain.sync.ForegroundSyncGateway
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
    private lateinit var selectedDateRepository: SelectedDateStore
    private lateinit var scoringCalculator: ScoringCalculator
    private lateinit var settingsRepo: UserPreferencesReader
    private lateinit var getWorkoutDisplayMetricsUseCase: GetWorkoutDisplayMetricsUseCase
    private lateinit var foregroundSyncController: ForegroundSyncGateway
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
                        classification =
                            WorkoutLoadClassification(
                                totalTrimp = 50.0,
                                trimpPerMinute = 1.2,
                                baseLoad = WorkoutLoadLevel.LIGHT,
                                intensity = WorkoutIntensityLevel.LIGHT,
                                finalLoad = WorkoutLoadLevel.LIGHT,
                                wasPromoted = false,
                            ),
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
                    classification =
                        WorkoutLoadClassification(
                            totalTrimp = 115.6,
                            trimpPerMinute = 1.93,
                            baseLoad = WorkoutLoadLevel.MODERATE,
                            intensity = WorkoutIntensityLevel.HARD,
                            finalLoad = WorkoutLoadLevel.HARD,
                            wasPromoted = true,
                        ),
                )

            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            val state = viewModel.uiState.first { it.recentWorkouts.isNotEmpty() }

            assertEquals(0.37f, state.recentWorkouts.single().gainedStrain)
            assertEquals("0.37", state.recentWorkouts.single().gainedStrainDisplay)
            assertEquals(116, state.recentWorkouts.single().computedTrimp)
            assertEquals(
                WorkoutLoadLevel.HARD,
                state.recentWorkouts
                    .single()
                    .classification
                    ?.finalLoad,
            )

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
    fun `stats state sums todayStrainIncrease from workout-only gains`() =
        runTest(testDispatcher) {
            // Default strainLoadSourceMode is WORKOUT_ONLY: the daily delta must equal the sum
            // of the already-rounded per-workout gains shown in History, not an independent
            // whole-day ATL/CTL recompute.
            val today = LocalDate.now()
            val zoneId = java.time.ZoneId.of("UTC")
            val todayMidnight = today.atStartOfDay(zoneId)
            val workout1 =
                WorkoutData(
                    id = "strength-1",
                    startTime = todayMidnight.plusHours(8).toInstant().toEpochMilli(),
                    endTime =
                        todayMidnight
                            .plusHours(8)
                            .plusMinutes(41)
                            .toInstant()
                            .toEpochMilli(),
                    exerciseType = "strength_training",
                    durationMinutes = 41,
                    zone1Minutes = 0f,
                    zone2Minutes = 0f,
                    zone3Minutes = 0f,
                    zone4Minutes = 0f,
                    zone5Minutes = 0f,
                    trimp = 20f,
                    avgHr = 103f,
                )
            val workout2 =
                WorkoutData(
                    id = "running-1",
                    startTime = todayMidnight.plusHours(18).toInstant().toEpochMilli(),
                    endTime =
                        todayMidnight
                            .plusHours(18)
                            .plusMinutes(27)
                            .toInstant()
                            .toEpochMilli(),
                    exerciseType = "running",
                    durationMinutes = 27,
                    zone1Minutes = 0f,
                    zone2Minutes = 0f,
                    zone3Minutes = 0f,
                    zone4Minutes = 0f,
                    zone5Minutes = 0f,
                    trimp = 25f,
                    avgHr = 116f,
                )
            workoutsFlow.value = listOf(workout1, workout2)
            summariesFlow.value =
                listOf(
                    DailySummary(
                        date = today,
                        readinessWorkoutOnly = 72.5f,
                        strainRatioWorkoutOnly = 0.365f,
                        trimpWorkoutOnly = 45f,
                    ),
                )
            every { dailySummaryRepository.observeLatest() } returns flowOf(summariesFlow.value.single())
            coEvery { workoutRepository.getEarliestWorkoutTimestamp() } returns
                today
                    .minusDays(10)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()

            coEvery {
                getWorkoutDisplayMetricsUseCase.execute(workout = workout1, samples = emptyList())
            } returns
                WorkoutDisplayMetrics(
                    preciseTrimp = 20f,
                    computedTrimp = 20,
                    trimpDisplay = "20",
                    gainedStrain = 0.09f,
                    gainedStrainDisplay = "0.09",
                    classification =
                        WorkoutLoadClassification(
                            totalTrimp = 20.0,
                            trimpPerMinute = 2.5,
                            baseLoad = WorkoutLoadLevel.VERY_LIGHT,
                            intensity = WorkoutIntensityLevel.VERY_HARD,
                            finalLoad = WorkoutLoadLevel.VERY_LIGHT,
                            wasPromoted = false,
                        ),
                )
            coEvery {
                getWorkoutDisplayMetricsUseCase.execute(workout = workout2, samples = emptyList())
            } returns
                WorkoutDisplayMetrics(
                    preciseTrimp = 25f,
                    computedTrimp = 25,
                    trimpDisplay = "25",
                    gainedStrain = 0.09f,
                    gainedStrainDisplay = "0.09",
                    classification =
                        WorkoutLoadClassification(
                            totalTrimp = 25.0,
                            trimpPerMinute = 2.5,
                            baseLoad = WorkoutLoadLevel.VERY_LIGHT,
                            intensity = WorkoutIntensityLevel.VERY_HARD,
                            finalLoad = WorkoutLoadLevel.VERY_LIGHT,
                            wasPromoted = false,
                        ),
                )

            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            val state = viewModel.uiState.first { it.todayStrainIncrease != null }

            assertEquals(0.18f, state.todayStrainIncrease!!, 0.001f)

            collectJob.cancelAndJoin()
        }

    @Test
    fun `stats state computes todayStrainIncrease from whole-day ATL-CTL diff in everyday-HR mode`() =
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
            every { settingsRepo.userPreferences } returns
                MutableStateFlow(UserPreferences(strainLoadSourceMode = LoadSourceMode.EVERYDAY_HEART_RATE))
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
