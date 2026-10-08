package app.readylytics.health.feature.workouts

import androidx.lifecycle.viewModelScope
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.date.SelectedDateStore
import app.readylytics.health.core.model.domain.model.RecordType
import app.readylytics.health.core.model.domain.preferences.UserPreferencesReader
import app.readylytics.health.core.model.domain.repository.DailySummaryRepository
import app.readylytics.health.core.model.domain.repository.HeartRateRepository
import app.readylytics.health.core.model.domain.repository.WorkoutRepository
import app.readylytics.health.core.model.domain.sync.ForegroundSyncGateway
import app.readylytics.health.core.model.domain.workouts.WorkoutsLayoutRepository
import app.readylytics.health.core.scoring.domain.cardio.TrainingStressBalanceCalculator
import app.readylytics.health.core.scoring.domain.scoring.GenerateResidualFatigueCurveUseCase
import app.readylytics.health.core.scoring.domain.scoring.GetWorkoutDisplayMetricsUseCase
import app.readylytics.health.core.scoring.domain.scoring.ScoringCalculator
import app.readylytics.health.core.scoring.domain.workouts.weekly.ComputeWeeklyTrainingStatsUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutsClockBoundaryTest {
    private val testDispatcher = StandardTestDispatcher()

    private val fixedClock =
        Clock.fixed(Instant.parse("2026-01-01T10:30:00Z"), ZoneId.of("Pacific/Honolulu"))
    private val scoringZone = ZoneId.of("Pacific/Kiritimati")

    private lateinit var dailySummaryRepository: DailySummaryRepository
    private lateinit var workoutRepository: WorkoutRepository
    private lateinit var heartRateRepository: HeartRateRepository
    private lateinit var selectedDateRepository: SelectedDateStore
    private lateinit var settingsRepo: UserPreferencesReader
    private lateinit var foregroundSyncController: ForegroundSyncGateway
    private lateinit var workoutsLayoutRepository: WorkoutsLayoutRepository
    private lateinit var selectedRangeStore: WorkoutsSelectedRangeStore
    private lateinit var generateResidualFatigueCurveUseCase: GenerateResidualFatigueCurveUseCase

    private val selectedDateFlow = MutableStateFlow(LocalDate.of(2026, 1, 2))
    private val earliestDateFlow = MutableStateFlow<LocalDate?>(null)
    private val isSyncingFlow = MutableStateFlow(false)
    private val preferencesFlow =
        MutableStateFlow(UserPreferences(scoringZoneId = scoringZone.id))

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        dailySummaryRepository =
            mockk {
                every { observeLatest() } returns flowOf(null)
                coEvery { getByDate(any()) } returns null
                every { observeSince(any()) } returns flowOf(emptyList())
            }
        workoutRepository =
            mockk {
                coEvery { getEarliestWorkoutTimestamp() } returns null
                coEvery { getCanonicalFatigueSeed(any()) } returns emptyList()
                coEvery { countByTimeRange(any(), any()) } returns 0
                coEvery { getInRangePaged(any(), any(), any(), any()) } returns emptyList()
                coEvery { getInRange(any(), any()) } returns emptyList()
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
            }
        settingsRepo =
            mockk {
                every { userPreferences } returns preferencesFlow
            }
        foregroundSyncController =
            mockk {
                every { isSyncing } returns isSyncingFlow
            }
        workoutsLayoutRepository =
            mockk {
                every { workoutCardConfigurations() } returns flowOf(emptyList())
                every { workoutChartConfigurations() } returns flowOf(emptyList())
                every { workoutHistoryConfigurations() } returns flowOf(emptyList())
            }
        selectedRangeStore = mockk(relaxed = true)
        generateResidualFatigueCurveUseCase =
            mockk {
                coEvery {
                    execute(
                        startDate = any(),
                        endDate = any(),
                        zoneId = any(),
                        config = any(),
                        retainedWorkouts = any(),
                        nowMs = any(),
                    )
                } returns emptyList()
            }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): WorkoutsViewModel =
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
            scoringCalculators =
                WorkoutsScoringCalculators(
                    scoringCalculator = mockk<ScoringCalculator>(relaxed = true),
                    trainingStressBalanceCalculator = mockk<TrainingStressBalanceCalculator>(relaxed = true),
                ),
            foregroundSyncController = foregroundSyncController,
            selectedRangeStore = selectedRangeStore,
            dispatchers =
                WorkoutsDispatchers(
                    io = testDispatcher,
                    default = testDispatcher,
                ),
            useCases =
                WorkoutsUseCases(
                    getWorkoutDisplayMetrics = mockk<GetWorkoutDisplayMetricsUseCase>(relaxed = true),
                    computeWeeklyTrainingStats = mockk<ComputeWeeklyTrainingStatsUseCase>(relaxed = true),
                    distancePermissionGate =
                        mockk<WorkoutsDistancePermissionGate> {
                            coEvery { isGranted() } returns
                                false
                        },
                    generateResidualFatigueCurve = generateResidualFatigueCurveUseCase,
                ),
            clock = fixedClock,
        )

    @Test
    fun `workouts query branches on today in scoring zone and clips fatigue curve to clock millis`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()

            // Today in Kiritimati is Jan 2 (2026-01-02)
            viewModel.uiState.first { !it.isLoading }
            verify { dailySummaryRepository.observeLatest() }

            coVerify {
                generateResidualFatigueCurveUseCase.execute(
                    startDate = any(),
                    endDate = any(),
                    zoneId = any(),
                    config = any(),
                    retainedWorkouts = any(),
                    nowMs = fixedClock.millis(),
                )
            }

            // Switch to Jan 1 (yesterday in Kiritimati)
            selectedDateFlow.value = LocalDate.of(2026, 1, 1)
            viewModel.uiState.first { it.selectedDate == LocalDate.of(2026, 1, 1) }

            val expectedYesterdayMidnightMs = Instant.parse("2025-12-31T10:00:00Z").toEpochMilli()
            coVerify { dailySummaryRepository.getByDate(expectedYesterdayMidnightMs) }

            viewModel.viewModelScope.cancel()
        }
}
