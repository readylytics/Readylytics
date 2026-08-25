package app.readylytics.health.feature.workouts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.date.SelectedDateStore
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.preferences.UserPreferencesReader
import app.readylytics.health.core.model.domain.repository.DailySummaryRepository
import app.readylytics.health.core.model.domain.repository.HeartRateRepository
import app.readylytics.health.core.model.domain.repository.WorkoutData
import app.readylytics.health.core.model.domain.repository.WorkoutRepository
import app.readylytics.health.core.model.domain.scoring.LoadSourceMode
import app.readylytics.health.core.model.domain.scoring.WorkoutIntensityLevel
import app.readylytics.health.core.model.domain.scoring.WorkoutLoadLevel
import app.readylytics.health.core.model.domain.sync.ForegroundSyncGateway
import app.readylytics.health.core.model.domain.workouts.WorkoutsLayoutRepository
import app.readylytics.health.core.scoring.domain.scoring.GetWorkoutDisplayMetricsUseCase
import app.readylytics.health.core.scoring.domain.scoring.ScoringCalculator
import app.readylytics.health.core.scoring.domain.scoring.WorkoutDisplayMetrics
import app.readylytics.health.core.scoring.domain.scoring.WorkoutLoadClassification
import app.readylytics.health.core.ui.common.TimeRange
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

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
    private lateinit var workoutsLayoutRepository: WorkoutsLayoutRepository
    private lateinit var savedStateHandle: SavedStateHandle

    private lateinit var viewModel: WorkoutsViewModel

    private val selectedDateFlow = MutableStateFlow(LocalDate.now())
    private val earliestDateFlow = MutableStateFlow<LocalDate?>(null)
    private val isSyncingFlow = MutableStateFlow(false)
    private val workouts = mutableListOf<WorkoutData>()
    private var workoutCount: Int? = null
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
                coEvery { countByTimeRange(any(), any()) } answers {
                    workoutCount
                        ?: workouts.count {
                            it.startTime >= firstArg<Long>() && it.startTime < secondArg<Long>()
                        }
                }
                coEvery { getInRangePaged(any(), any(), any(), any()) } answers {
                    val fromMs = firstArg<Long>()
                    val toMs = secondArg<Long>()
                    val limit = thirdArg<Int>()
                    val offset = args[3] as Int
                    workouts.filter { it.startTime >= fromMs && it.startTime < toMs }.drop(offset).take(limit)
                }
                coEvery { getInRange(any(), any()) } answers {
                    val fromMs = firstArg<Long>()
                    val toMs = secondArg<Long>()
                    workouts.filter { it.startTime >= fromMs && it.startTime < toMs }
                }
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
                every { userPreferences } returns preferencesFlow
            }
        getWorkoutDisplayMetricsUseCase =
            mockk(relaxed = true) {
                coEvery {
                    execute(
                        workout = any(),
                        samples = any(),
                        preferences = any(),
                        historicalSummaries = any(),
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
        workoutsLayoutRepository =
            mockk {
                every { workoutCardConfigurations() } returns
                    flowOf(app.readylytics.health.core.model.data.preferences.SettingsDefaults.DEFAULT_WORKOUT_CARDS)
                every { workoutChartConfigurations() } returns
                    flowOf(app.readylytics.health.core.model.data.preferences.SettingsDefaults.DEFAULT_WORKOUT_CHARTS)
                every { workoutHistoryConfigurations() } returns
                    flowOf(app.readylytics.health.core.model.data.preferences.SettingsDefaults.DEFAULT_WORKOUT_HISTORY)
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
            workoutsLayoutRepository = workoutsLayoutRepository,
            savedStateHandle = savedStateHandle,
            dispatchers = WorkoutsDispatchers(testDispatcher, testDispatcher),
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
            workouts.addAll(dummyWorkouts)

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
            workouts.addAll(dummyWorkouts)

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
            workouts.addAll(dummyWorkouts)

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
    fun `page two requests offset ten and exposes only that page`() =
        runTest(testDispatcher) {
            workouts.addAll(workoutPageFixtures(25))

            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            viewModel.uiState.first { it.recentWorkouts.isNotEmpty() }

            viewModel.onNextPage()
            val pageTwo = viewModel.uiState.first { it.currentPage == 2 }

            assertEquals(10, pageTwo.recentWorkouts.size)
            assertEquals(
                "11",
                pageTwo.recentWorkouts
                    .first()
                    .workout.id,
            )
            assertEquals(
                "20",
                pageTwo.recentWorkouts
                    .last()
                    .workout.id,
            )

            coVerify { workoutRepository.getInRangePaged(any(), any(), 10, 10) }

            collectJob.cancel()
        }

    @Test
    fun `final page holds the remainder`() =
        runTest(testDispatcher) {
            workouts.addAll(workoutPageFixtures(25))

            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            viewModel.uiState.first { it.recentWorkouts.isNotEmpty() }

            viewModel.onNextPage()
            viewModel.uiState.first { it.currentPage == 2 }
            viewModel.onNextPage()
            val pageThree = viewModel.uiState.first { it.currentPage == 3 }

            assertEquals(5, pageThree.recentWorkouts.size)
            assertEquals(
                "21",
                pageThree.recentWorkouts
                    .first()
                    .workout.id,
            )
            assertEquals(
                "25",
                pageThree.recentWorkouts
                    .last()
                    .workout.id,
            )

            collectJob.cancel()
        }

    @Test
    fun `shrinking repository count clamps the page`() =
        runTest(testDispatcher) {
            workouts.addAll(workoutPageFixtures(25))

            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            viewModel.uiState.first { it.recentWorkouts.isNotEmpty() }

            viewModel.onNextPage()
            viewModel.uiState.first { it.currentPage == 2 }
            viewModel.onNextPage()
            viewModel.uiState.first { it.currentPage == 3 }
            assertEquals(3, viewModel.currentPage.value)

            // The repository count collapses to a single page; the next pipeline run must clamp.
            workoutCount = 5
            viewModel.onPreviousPage()
            val clamped = viewModel.uiState.first { it.totalPages == 1 }
            assertEquals(1, clamped.currentPage)
            assertEquals(1, clamped.totalPages)

            collectJob.cancel()
        }

    @Test
    fun `previous press reaches page 1 with no dead press when count shrinks to two pages`() =
        runTest(testDispatcher) {
            workouts.addAll(workoutPageFixtures(25))

            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            viewModel.uiState.first { it.recentWorkouts.isNotEmpty() }

            viewModel.onNextPage()
            viewModel.uiState.first { it.currentPage == 2 }
            viewModel.onNextPage()
            viewModel.uiState.first { it.currentPage == 3 }
            assertEquals(3, viewModel.currentPage.value)

            // A resync/cleanup shrinks the range to 15 items (two pages) and re-emits the
            // daily-summary flow. The pipeline clamps the displayed page to 2 while the raw
            // _currentPage stays 3.
            workoutCount = 15
            summariesFlow.value = listOf(DailySummary(date = LocalDate.now(), trimpWorkoutOnly = 0f))
            viewModel.uiState.first { it.totalPages == 2 }
            assertEquals(2, viewModel.uiState.value.currentPage)
            assertEquals(3, viewModel.currentPage.value)

            viewModel.onPreviousPage()
            testScheduler.advanceUntilIdle()
            assertEquals(1, viewModel.uiState.value.currentPage)

            collectJob.cancel()
        }

    @Test
    fun `display metrics are computed only for visible page rows`() =
        runTest(testDispatcher) {
            // Yesterday's workouts: inside the display window but outside the selected (today)
            // window, so the selected-day strain derivation maps nothing extra.
            val yesterday =
                LocalDate
                    .now()
                    .minusDays(1)
                    .atStartOfDay(java.time.ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            workouts.addAll(workoutPageFixtures(25, startTimeMs = yesterday + 23 * 60 * 60 * 1000L))

            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            val state = viewModel.uiState.first { it.recentWorkouts.isNotEmpty() }

            assertEquals(10, state.recentWorkouts.size)
            assertEquals(3, state.totalPages)

            coVerify(exactly = 10) {
                getWorkoutDisplayMetricsUseCase.execute(any(), any(), any(), any())
            }

            collectJob.cancel()
        }

    private fun workoutPageFixtures(
        count: Int,
        startTimeMs: Long = System.currentTimeMillis(),
    ): List<WorkoutData> =
        (1..count).map { id ->
            WorkoutData(
                id = id.toString(),
                startTime = startTimeMs - (id * 1000 * 60),
                endTime = startTimeMs - (id * 1000 * 60) + 30 * 1000L,
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
            workouts.add(workout)
            summariesFlow.value = listOf(DailySummary(date = today, trimpWorkoutOnly = 115.6f, rhrBpm = 52f))
            coEvery {
                getWorkoutDisplayMetricsUseCase.execute(
                    workout = workout,
                    samples = emptyList(),
                    preferences = any(),
                    historicalSummaries = any(),
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
            workouts.addAll(listOf(workout1, workout2))
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
                getWorkoutDisplayMetricsUseCase.execute(
                    workout = workout1,
                    samples = emptyList(),
                    preferences = any(),
                    historicalSummaries = any(),
                )
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
                getWorkoutDisplayMetricsUseCase.execute(
                    workout = workout2,
                    samples = emptyList(),
                    preferences = any(),
                    historicalSummaries = any(),
                )
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
                    DailySummary(date = today.minusDays(8), trimpEverydayHr = 5f),
                    DailySummary(
                        date = today,
                        readinessWorkoutOnly = 72.5f,
                        strainRatioWorkoutOnly = 0.365f,
                        trimpEverydayHr = 15f,
                    ),
                )
            every { dailySummaryRepository.observeLatest() } returns
                flowOf(summariesFlow.value.first { it.date == today })
            preferencesFlow.value =
                UserPreferences(strainLoadSourceMode = LoadSourceMode.EVERYDAY_HEART_RATE)

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

    @Test
    fun `todayStrainIncrease is non-null in everyday-HR mode with thirty days of summaries and zero workouts`() =
        runTest(testDispatcher) {
            val today = LocalDate.now()
            summariesFlow.value =
                (0..29).map { daysAgo ->
                    DailySummary(date = today.minusDays(daysAgo.toLong()), trimpEverydayHr = 20f)
                }
            preferencesFlow.value =
                UserPreferences(strainLoadSourceMode = LoadSourceMode.EVERYDAY_HEART_RATE)

            every { scoringCalculator.computeCtlEmaSeries(any(), any(), any()) } returns mapOf(today to 10f)
            every { scoringCalculator.computeAtlEmaSeries(any(), any(), any()) } returns mapOf(today to 15f)
            every { scoringCalculator.computeStrainRatio(15f, 10f) } returns 1.5f
            every { scoringCalculator.computeStrainRatio(12f, 10f) } returns 1.2f
            every { scoringCalculator.computeAtlEmaWithDecay(match { it[today] == 0f }, today) } returns 12f
            every { scoringCalculator.computeCtlEmaWithDecay(match { it[today] == 0f }, today) } returns 10f

            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            val state = viewModel.uiState.first { it.todayStrainIncrease != null }

            assertEquals(0.3f, state.todayStrainIncrease!!, 0.001f)

            collectJob.cancelAndJoin()
        }

    @Test
    fun `scoring zone determines selected-day workout membership`() =
        runTest(testDispatcher) {
            val selectedDate = LocalDate.of(2026, 6, 9)
            val zoneId = ZoneId.of("Pacific/Honolulu")
            val originalDeviceZone = TimeZone.getDefault()
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
            try {
                selectedDateFlow.value = selectedDate
                preferencesFlow.value = UserPreferences(scoringZoneId = "Pacific/Honolulu")
                val startTime = Instant.parse("2026-06-10T05:00:00Z").toEpochMilli()
                val workout =
                    WorkoutData(
                        id = "zone-edge",
                        startTime = startTime,
                        endTime = startTime + 30 * 60_000L,
                        exerciseType = "running",
                        durationMinutes = 30,
                        zone1Minutes = 0f,
                        zone2Minutes = 0f,
                        zone3Minutes = 0f,
                        zone4Minutes = 0f,
                        zone5Minutes = 0f,
                        trimp = 30f,
                        avgHr = 120f,
                    )
                workouts.add(workout)

                viewModel = createViewModel()
                val collectJob = launch { viewModel.uiState.collect {} }
                testScheduler.advanceUntilIdle()

                assertEquals(
                    listOf("zone-edge"),
                    viewModel.uiState.value.recentWorkouts
                        .map { it.workout.id },
                )

                collectJob.cancelAndJoin()
            } finally {
                TimeZone.setDefault(originalDeviceZone)
            }
        }

    @Test
    fun `new workout history can cross seven-day tenure without resubscribing`() =
        runTest(testDispatcher) {
            val selectedDate = selectedDateFlow.value
            val zoneId = ZoneId.systemDefault()
            coEvery { workoutRepository.getEarliestWorkoutTimestamp() } returnsMany
                listOf(
                    selectedDate
                        .minusDays(5)
                        .atStartOfDay(zoneId)
                        .toInstant()
                        .toEpochMilli(),
                    selectedDate
                        .minusDays(6)
                        .atStartOfDay(zoneId)
                        .toInstant()
                        .toEpochMilli(),
                )

            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            testScheduler.advanceUntilIdle()
            assertNull(viewModel.uiState.value.todayStrainIncrease)

            // A sync that extends history re-emits the daily-summary flow, re-running the
            // pipeline body (which re-derives tenure) without resubscribing to a workout flow.
            summariesFlow.value = listOf(DailySummary(date = selectedDate, trimpWorkoutOnly = 0f))
            testScheduler.advanceUntilIdle()

            assertEquals(0f, viewModel.uiState.value.todayStrainIncrease!!, 0.001f)

            collectJob.cancelAndJoin()
        }

    @Test
    fun `isSyncing toggle does not restart the heavy pipeline`() =
        runTest(testDispatcher) {
            val workout =
                WorkoutData(
                    id = "run-1",
                    startTime = System.currentTimeMillis() - 1000 * 60 * 30,
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
            workouts.add(workout)

            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            testScheduler.advanceUntilIdle()

            val stateBeforeToggle = viewModel.uiState.first { it.recentWorkouts.isNotEmpty() }
            assertEquals(false, stateBeforeToggle.isLoading)
            assertEquals(false, stateBeforeToggle.isRefreshing)

            isSyncingFlow.value = true
            testScheduler.advanceUntilIdle()
            // Workouts are already present, so this is a routine refresh, not a first load:
            // isLoading must stay false (no skeleton/chart rebuild) and only isRefreshing flips.
            assertEquals(false, viewModel.uiState.value.isLoading)
            assertEquals(true, viewModel.uiState.value.isRefreshing)

            isSyncingFlow.value = false
            testScheduler.advanceUntilIdle()
            val stateAfterToggle = viewModel.uiState.value
            assertEquals(false, stateAfterToggle.isLoading)
            assertEquals(false, stateAfterToggle.isRefreshing)

            // The heavy pipeline (paged history reads, tenure derivation, EMA series) must not
            // restart on a sync toggle -- only the cheap isLoading/isRefreshing merge should run.
            // getInRangePaged/countByTimeRange run once per pipeline body, so an exactly-once
            // verification proves the body did not re-run; getEarliestWorkoutTimestamp proves the
            // WORKOUT_ONLY tenure derivation did not re-run, and assertSame proves the emitted
            // items were not recomputed.
            coVerify(exactly = 1) { workoutRepository.getInRangePaged(any(), any(), any(), any()) }
            coVerify(exactly = 1) { workoutRepository.countByTimeRange(any(), any()) }
            coVerify(exactly = 1) { workoutRepository.getEarliestWorkoutTimestamp() }
            assertSame(stateBeforeToggle.recentWorkouts, stateAfterToggle.recentWorkouts)

            collectJob.cancel()
        }

    @Test
    fun unrelatedPreferenceChange_doesNotRestartDatabaseSubscriptions() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            testScheduler.advanceUntilIdle()

            preferencesFlow.value =
                preferencesFlow.value.copy(
                    dynamicColorEnabled = !preferencesFlow.value.dynamicColorEnabled,
                )
            testScheduler.advanceUntilIdle()

            // Room subscriptions (observeLatest, observeSince) are created once per pipeline
            // restart; an unrelated preference change must not recreate them.
            verify(exactly = 1) { dailySummaryRepository.observeLatest() }
            verify(exactly = 2) { dailySummaryRepository.observeSince(any()) }
            collectJob.cancelAndJoin()
        }

    @Test
    fun `isLoading stays true while syncing when no workouts or summary exist yet`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            testScheduler.advanceUntilIdle()

            isSyncingFlow.value = true
            testScheduler.advanceUntilIdle()
            val state = viewModel.uiState.value
            assertEquals(true, state.isLoading)
            assertEquals(true, state.isRefreshing)

            collectJob.cancel()
        }

    @Test
    fun `heart-rate samples are batched, not fetched once per workout`() =
        runTest(testDispatcher) {
            // 5 close-together workouts must collapse into one getByTimeRange call per fetch
            // (F10) instead of one query per workout. After pagination there are two fetches:
            // the visible page (5 workouts) and the selected-day strain derivation (5 workouts),
            // each collapsed into a single batched query.
            val dummyWorkouts =
                (1..5).map { id ->
                    WorkoutData(
                        id = id.toString(),
                        startTime = System.currentTimeMillis() - (id * 1000 * 60),
                        endTime = System.currentTimeMillis() - (id * 1000 * 60) + 1000 * 30,
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
            workouts.addAll(dummyWorkouts)

            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            testScheduler.advanceUntilIdle()

            viewModel.uiState.first { it.recentWorkouts.size == 5 }

            coVerify(exactly = 2) { heartRateRepository.getByTimeRange(any(), any()) }

            collectJob.cancel()
        }

    @Test
    fun `weekly training stats compare week-to-date against the like-for-like previous window`() =
        runTest(testDispatcher) {
            // Thursday 2026-06-04; Monday-start week = Jun 1..4, previous window = May 25..28.
            selectedDateFlow.value = LocalDate.of(2026, 6, 4)
            workouts.addAll(
                listOf(
                    workoutOnDate(LocalDate.of(2026, 6, 2), durationMinutes = 30),
                    workoutOnDate(LocalDate.of(2026, 5, 26), durationMinutes = 60),
                    workoutOnDate(LocalDate.of(2026, 5, 29), durationMinutes = 999), // prev Fri — outside window
                ),
            )

            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            testScheduler.advanceUntilIdle()

            val stats = viewModel.uiState.value.weeklyTraining!!
            assertEquals(30, stats.currentWeek.totalDurationMinutes)
            assertEquals(60, stats.previousWeek.totalDurationMinutes)
            assertEquals(-30, stats.comparison.durationDeltaMinutes)
            assertEquals(1, stats.currentWeek.workoutCount)
            assertEquals(1, stats.currentWeek.activeDays)
            collectJob.cancel()
        }

    @Test
    fun `weekly training is null before any load completes`() =
        runTest(testDispatcher) {
            viewModel = createViewModel()

            assertNull(viewModel.uiState.value.weeklyTraining)
        }

    @Test
    fun `weekly training updates when workout data refreshes`() =
        runTest(testDispatcher) {
            selectedDateFlow.value = LocalDate.of(2026, 6, 4)
            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            testScheduler.advanceUntilIdle()
            assertEquals(
                0,
                viewModel.uiState.value.weeklyTraining!!
                    .currentWeek.workoutCount,
            )

            workouts.addAll(listOf(workoutOnDate(LocalDate.of(2026, 6, 2), durationMinutes = 30)))
            summariesFlow.value = listOf(mockk<DailySummary>(relaxed = true))
            testScheduler.advanceUntilIdle()
            assertEquals(
                1,
                viewModel.uiState.value.weeklyTraining!!
                    .currentWeek.workoutCount,
            )
            collectJob.cancel()
        }

    @Test
    fun `changing the week start day preference recomputes weekly training`() =
        runTest(testDispatcher) {
            // Thursday 2026-06-04. Sunday-start week contains Sun May 31; Monday-start does not.
            selectedDateFlow.value = LocalDate.of(2026, 6, 4)
            workouts.addAll(listOf(workoutOnDate(LocalDate.of(2026, 5, 31), durationMinutes = 60)))

            viewModel = createViewModel()
            val collectJob = launch { viewModel.uiState.collect {} }
            testScheduler.advanceUntilIdle()
            assertEquals(
                0,
                viewModel.uiState.value.weeklyTraining!!
                    .currentWeek.totalDurationMinutes,
            )

            preferencesFlow.value = preferencesFlow.value.copy(weekStartDay = DayOfWeek.SUNDAY)
            testScheduler.advanceUntilIdle()
            assertEquals(
                60,
                viewModel.uiState.value.weeklyTraining!!
                    .currentWeek.totalDurationMinutes,
            )
            collectJob.cancel()
        }

    private fun workoutOnDate(
        date: LocalDate,
        durationMinutes: Int,
    ): WorkoutData {
        val epochMillis =
            date
                .atTime(12, 0)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        return WorkoutData(
            id = "workout-$epochMillis-$durationMinutes",
            startTime = epochMillis,
            endTime = epochMillis + durationMinutes * 60_000L,
            exerciseType = "running",
            durationMinutes = durationMinutes,
            zone1Minutes = 0f,
            zone2Minutes = 0f,
            zone3Minutes = 0f,
            zone4Minutes = 0f,
            zone5Minutes = 0f,
            trimp = 50f,
            avgHr = 130f,
        )
    }
}
