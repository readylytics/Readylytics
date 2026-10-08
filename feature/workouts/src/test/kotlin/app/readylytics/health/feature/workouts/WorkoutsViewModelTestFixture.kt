package app.readylytics.health.feature.workouts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.date.SelectedDateStore
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.RecordType
import app.readylytics.health.core.model.domain.preferences.UserPreferencesReader
import app.readylytics.health.core.model.domain.repository.DailySummaryRepository
import app.readylytics.health.core.model.domain.repository.HeartRateRepository
import app.readylytics.health.core.model.domain.repository.WorkoutData
import app.readylytics.health.core.model.domain.repository.WorkoutRepository
import app.readylytics.health.core.model.domain.scoring.WorkoutIntensityLevel
import app.readylytics.health.core.model.domain.scoring.WorkoutLoadLevel
import app.readylytics.health.core.model.domain.sync.ForegroundSyncGateway
import app.readylytics.health.core.model.domain.workouts.WorkoutsLayoutRepository
import app.readylytics.health.core.scoring.domain.cardio.TrainingStressBalanceCalculator
import app.readylytics.health.core.scoring.domain.scoring.GetWorkoutDisplayMetricsUseCase
import app.readylytics.health.core.scoring.domain.scoring.ScoringCalculator
import app.readylytics.health.core.scoring.domain.scoring.WorkoutDisplayMetrics
import app.readylytics.health.core.scoring.domain.scoring.WorkoutLoadClassification
import app.readylytics.health.core.scoring.domain.workouts.weekly.ComputeWeeklyTrainingStatsUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
abstract class WorkoutsViewModelTestFixture {
    protected val testDispatcher = StandardTestDispatcher()

    protected lateinit var dailySummaryRepository: DailySummaryRepository
    protected lateinit var workoutRepository: WorkoutRepository
    protected lateinit var heartRateRepository: HeartRateRepository
    protected lateinit var selectedDateRepository: SelectedDateStore
    protected lateinit var scoringCalculator: ScoringCalculator
    protected lateinit var settingsRepo: UserPreferencesReader
    protected lateinit var getWorkoutDisplayMetricsUseCase: GetWorkoutDisplayMetricsUseCase
    protected lateinit var foregroundSyncController: ForegroundSyncGateway
    protected lateinit var workoutsLayoutRepository: WorkoutsLayoutRepository
    protected lateinit var savedStateHandle: SavedStateHandle

    protected lateinit var viewModel: WorkoutsViewModel

    protected val selectedDateFlow = MutableStateFlow(LocalDate.now())
    protected val earliestDateFlow = MutableStateFlow<LocalDate?>(null)
    protected val isSyncingFlow = MutableStateFlow(false)
    protected val workouts = mutableListOf<WorkoutData>()
    protected var workoutCount: Int? = null
    protected val summariesFlow = MutableStateFlow<List<DailySummary>>(emptyList())
    protected val preferencesFlow = MutableStateFlow(UserPreferences())

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        initRepositories()
        initServices()
    }

    private fun initRepositories() {
        dailySummaryRepository =
            mockk {
                every { observeLatest() } returns flowOf(null)
                coEvery { getByDate(any()) } returns null
                every { observeSince(any()) } returns summariesFlow
            }
        workoutRepository =
            mockk {
                coEvery { getEarliestWorkoutTimestamp() } returns null
                coEvery { getCanonicalFatigueSeed(any()) } returns emptyList()
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
                coEvery { countInRangeOfType(RecordType.EXERCISE.name, any(), any()) } returns 0
                coEvery { getByTimeRangeOfType(RecordType.EXERCISE.name, any(), any()) } returns emptyList()
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
    }

    private fun initServices() {
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
                } returns defaultDisplayMetrics()
            }
        foregroundSyncController =
            mockk {
                every { isSyncing } returns isSyncingFlow
            }
        workoutsLayoutRepository =
            mockk {
                every { workoutCardConfigurations() } returns
                    flowOf(SettingsDefaults.DEFAULT_WORKOUT_CARDS)
                every { workoutChartConfigurations() } returns
                    flowOf(SettingsDefaults.DEFAULT_WORKOUT_CHARTS)
                every { workoutHistoryConfigurations() } returns
                    flowOf(SettingsDefaults.DEFAULT_WORKOUT_HISTORY)
            }
        savedStateHandle = SavedStateHandle()
    }

    protected fun createViewModel(clock: Clock = Clock.systemDefaultZone()): WorkoutsViewModel =
        WorkoutsViewModel(
            repositories =
                WorkoutsRepositories(
                    dailySummary = dailySummaryRepository,
                    workout = workoutRepository,
                    heartRate = heartRateRepository,
                    selectedDate = selectedDateRepository,
                    settings = settingsRepo,
                    layout = workoutsLayoutRepository,
                ),
            scoringCalculators = WorkoutsScoringCalculators(scoringCalculator, TrainingStressBalanceCalculator()),
            foregroundSyncController = foregroundSyncController,
            selectedRangeStore = WorkoutsSelectedRangeStore(savedStateHandle),
            dispatchers = WorkoutsDispatchers(testDispatcher, testDispatcher),
            useCases =
                WorkoutsUseCases(
                    getWorkoutDisplayMetricsUseCase,
                    ComputeWeeklyTrainingStatsUseCase(),
                    app.readylytics.health.core.scoring.domain.scoring
                        .GenerateResidualFatigueCurveUseCase(),
                    WorkoutsDistancePermissionGate { true },
                ),
            clock = clock,
        )

    @After
    fun tearDown() =
        runTest(testDispatcher) {
            if (::viewModel.isInitialized) {
                viewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            }
            Dispatchers.resetMain()
        }

    protected fun workoutPageFixtures(
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

    protected fun workoutOnDate(
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

    protected fun arrangeRoundedLoadMetrics() {
        val today = LocalDate.now()
        val startMs =
            today
                .atStartOfDay(ZoneId.systemDefault())
                .plusHours(8)
                .toInstant()
                .toEpochMilli()
        val workout = createTimedWorkout("run-1", startMs, durationMinutes = 60, trimp = 115.6f, avgHr = 134f)
        workouts.add(workout)
        summariesFlow.value = listOf(DailySummary(date = today, trimpWorkoutOnly = 115.6f, rhrBpm = 52f))
        mockDisplayMetrics(
            workout,
            preciseTrimp = 115.6f,
            computedTrimp = 116,
            gainedStrain = 0.37f,
            finalLoad = WorkoutLoadLevel.HARD,
        )
    }

    protected fun arrangeWorkoutOnlyGains() {
        val today = LocalDate.now()
        val zoneId = ZoneId.of("UTC")
        val todayMidnight = today.atStartOfDay(zoneId)
        val workout1 =
            createTimedWorkout("strength-1", todayMidnight.plusHours(8).toInstant().toEpochMilli(), 41, 20f, 103f)
        val workout2 =
            createTimedWorkout("running-1", todayMidnight.plusHours(18).toInstant().toEpochMilli(), 27, 25f, 116f)
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

        mockDisplayMetrics(
            workout1,
            preciseTrimp = 20f,
            computedTrimp = 20,
            gainedStrain = 0.09f,
            finalLoad = WorkoutLoadLevel.VERY_LIGHT,
        )
        mockDisplayMetrics(
            workout2,
            preciseTrimp = 25f,
            computedTrimp = 25,
            gainedStrain = 0.09f,
            finalLoad = WorkoutLoadLevel.VERY_LIGHT,
        )
    }

    private fun mockDisplayMetrics(
        workout: WorkoutData,
        preciseTrimp: Float,
        computedTrimp: Int,
        gainedStrain: Float,
        finalLoad: WorkoutLoadLevel,
    ) {
        coEvery {
            getWorkoutDisplayMetricsUseCase.execute(
                workout = workout,
                samples = emptyList(),
                preferences = any(),
                historicalSummaries = any(),
            )
        } returns
            WorkoutDisplayMetrics(
                preciseTrimp = preciseTrimp,
                computedTrimp = computedTrimp,
                trimpDisplay = computedTrimp.toString(),
                gainedStrain = gainedStrain,
                gainedStrainDisplay = gainedStrain.toString(),
                classification =
                    WorkoutLoadClassification(
                        totalTrimp = preciseTrimp.toDouble(),
                        trimpPerMinute = 1.93,
                        baseLoad = WorkoutLoadLevel.MODERATE,
                        intensity = WorkoutIntensityLevel.HARD,
                        finalLoad = finalLoad,
                        wasPromoted = true,
                    ),
            )
    }
}

private fun defaultDisplayMetrics(): WorkoutDisplayMetrics =
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

internal fun createTimedWorkout(
    id: String,
    startTimeMs: Long,
    durationMinutes: Int,
    trimp: Float,
    avgHr: Float,
): WorkoutData =
    WorkoutData(
        id = id,
        startTime = startTimeMs,
        endTime = startTimeMs + durationMinutes * 60_000L,
        exerciseType = "running",
        durationMinutes = durationMinutes,
        zone1Minutes = 0f,
        zone2Minutes = 0f,
        zone3Minutes = 0f,
        zone4Minutes = 0f,
        zone5Minutes = 0f,
        trimp = trimp,
        avgHr = avgHr,
    )
