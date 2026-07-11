package app.readylytics.health.feature.workouts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.preferences.UserPreferencesReader
import app.readylytics.health.domain.repository.DailySummaryRepository
import app.readylytics.health.domain.repository.HealthConnectRepository
import app.readylytics.health.domain.repository.HeartRateRecordData
import app.readylytics.health.domain.repository.HeartRateRepository
import app.readylytics.health.domain.repository.WorkoutData
import app.readylytics.health.domain.repository.WorkoutRepository
import app.readylytics.health.domain.scoring.GetWorkoutDisplayMetricsUseCase
import app.readylytics.health.domain.scoring.WorkoutDisplayMetrics
import app.readylytics.health.domain.scoring.WorkoutIntensityLevel
import app.readylytics.health.domain.scoring.WorkoutLoadClassification
import app.readylytics.health.domain.scoring.WorkoutLoadLevel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutDetailViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var viewModel: WorkoutDetailViewModel

    private val workoutRepository = mockk<WorkoutRepository>()
    private val healthConnectRepository = mockk<HealthConnectRepository>(relaxed = true)
    private val heartRateRepository = mockk<HeartRateRepository>(relaxed = true)
    private val dailySummaryRepository = mockk<DailySummaryRepository>(relaxed = true)
    private val settingsRepository =
        mockk<UserPreferencesReader> {
            every { userPreferences } returns MutableStateFlow(UserPreferences())
        }
    private val getWorkoutDisplayMetricsUseCase = mockk<GetWorkoutDisplayMetricsUseCase>()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        viewModel =
            WorkoutDetailViewModel(
                workoutRepository = workoutRepository,
                hcRepo = healthConnectRepository,
                heartRateRepository = heartRateRepository,
                dailySummaryRepository = dailySummaryRepository,
                settingsRepo = settingsRepository,
                getWorkoutDisplayMetricsUseCase = getWorkoutDisplayMetricsUseCase,
                savedStateHandle = SavedStateHandle(),
            )
    }

    @After
    fun tearDown() {
        if (::viewModel.isInitialized) {
            viewModel.viewModelScope.cancel()
        }
        Dispatchers.resetMain()
    }

    @Test
    fun `detail state uses rounded load metrics from shared use case`() =
        runTest {
            val date = LocalDate.of(2026, 6, 9)
            val startMs =
                date
                    .atStartOfDay(ZoneId.systemDefault())
                    .plusHours(19)
                    .plusMinutes(28)
                    .toInstant()
                    .toEpochMilli()
            val workout =
                WorkoutData(
                    id = "run-1",
                    startTime = startMs,
                    endTime = startMs + 62 * 60 * 1000L,
                    exerciseType = "running",
                    durationMinutes = 62,
                    zone1Minutes = 0f,
                    zone2Minutes = 10f,
                    zone3Minutes = 20f,
                    zone4Minutes = 32f,
                    zone5Minutes = 0f,
                    trimp = 115.6f,
                    avgHr = 134f,
                )
            val dbSamples =
                listOf(
                    HeartRateRecordData(
                        id = "hr-1",
                        timestampMs = workout.startTime + 1_000L,
                        beatsPerMinute = 134,
                        recordType = "EXERCISE",
                    ),
                )
            coEvery { workoutRepository.getById("run-1") } returns workout
            coEvery { healthConnectRepository.readHeartRateSamples(any(), any()) } returns emptyList()
            coEvery { heartRateRepository.getByTimeRange(any(), any()) } returns dbSamples
            coEvery { dailySummaryRepository.getByDate(any()) } returns
                DailySummary(date = date, trimpWorkoutOnly = 115.6f, rhrBpm = 52f, totalRasWorkoutOnly = 12f)
            coEvery { dailySummaryRepository.getSince(any()) } returns
                listOf(DailySummary(date = date, trimpWorkoutOnly = 115.6f, rhrBpm = 52f, rasWorkoutOnly = 12f))
            coEvery {
                getWorkoutDisplayMetricsUseCase.execute(
                    workout = workout,
                    samples = any(),
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

            viewModel.loadWorkout("run-1")
            advanceUntilIdle()

            assertEquals(116, viewModel.uiState.value.computedTrimp)
            assertEquals(0.37f, viewModel.uiState.value.gainedStrain)
            assertEquals("0.37", viewModel.uiState.value.gainedStrainDisplay)
            assertEquals(
                WorkoutLoadLevel.HARD,
                viewModel.uiState.value.classification
                    ?.finalLoad,
            )
        }

    @Test
    fun `unknown workout ID produces controlled error state and never throws`() =
        runTest {
            coEvery { workoutRepository.getById("unknown-id") } returns null

            // Should not throw, just update state to not loading and workout = null
            viewModel.loadWorkout("unknown-id")
            advanceUntilIdle()

            assertEquals(null, viewModel.uiState.value.workout)
            assertEquals(false, viewModel.uiState.value.isLoading)
        }

    @Test
    fun `valid ID in SavedStateHandle triggers load and survives recreation`() =
        runTest {
            val date = LocalDate.of(2026, 6, 9)
            val startMs =
                date
                    .atStartOfDay(ZoneId.systemDefault())
                    .plusHours(19)
                    .plusMinutes(28)
                    .toInstant()
                    .toEpochMilli()
            val workout =
                WorkoutData(
                    id = "run-1",
                    startTime = startMs,
                    endTime = startMs + 62 * 60 * 1000L,
                    exerciseType = "running",
                    durationMinutes = 62,
                    zone1Minutes = 0f,
                    zone2Minutes = 10f,
                    zone3Minutes = 20f,
                    zone4Minutes = 32f,
                    zone5Minutes = 0f,
                    trimp = 115.6f,
                    avgHr = 134f,
                )
            coEvery { workoutRepository.getById("run-1") } returns workout
            coEvery { healthConnectRepository.readHeartRateSamples(any(), any()) } returns emptyList()
            coEvery { heartRateRepository.getByTimeRange(any(), any()) } returns emptyList()
            coEvery { dailySummaryRepository.getByDate(any()) } returns null
            coEvery { dailySummaryRepository.getSince(any()) } returns emptyList()
            coEvery {
                getWorkoutDisplayMetricsUseCase.execute(
                    workout = workout,
                    samples = any(),
                )
            } returns
                WorkoutDisplayMetrics(
                    preciseTrimp = 115.6f,
                    computedTrimp = 116,
                    trimpDisplay = "116",
                    gainedStrain = 0.37f,
                    gainedStrainDisplay = "0.37",
                    classification = null,
                )

            // Recreate viewModel with pre-populated SavedStateHandle simulating process death recovery
            val restoredHandle = SavedStateHandle(mapOf("workoutId" to "run-1"))
            val recreatedViewModel =
                WorkoutDetailViewModel(
                    workoutRepository = workoutRepository,
                    hcRepo = healthConnectRepository,
                    heartRateRepository = heartRateRepository,
                    dailySummaryRepository = dailySummaryRepository,
                    settingsRepo = settingsRepository,
                    getWorkoutDisplayMetricsUseCase = getWorkoutDisplayMetricsUseCase,
                    savedStateHandle = restoredHandle,
                )

            advanceUntilIdle()

            assertEquals(workout, recreatedViewModel.uiState.value.workout)
            assertEquals(116, recreatedViewModel.uiState.value.computedTrimp)
            assertEquals(false, recreatedViewModel.uiState.value.isLoading)
        }

    @Test
    fun `loadWorkout requests heart rate through end plus three minutes plus tolerance`() =
        runTest {
            val toleranceSeconds = 30
            every { settingsRepository.userPreferences } returns
                MutableStateFlow(UserPreferences(hrrToleranceSeconds = toleranceSeconds))
            val date = LocalDate.of(2026, 6, 9)
            val startMs =
                date
                    .atStartOfDay(ZoneId.systemDefault())
                    .plusHours(19)
                    .toInstant()
                    .toEpochMilli()
            val workout =
                WorkoutData(
                    id = "run-1",
                    startTime = startMs,
                    endTime = startMs + 62 * 60 * 1000L,
                    exerciseType = "running",
                    durationMinutes = 62,
                    zone1Minutes = 0f,
                    zone2Minutes = 10f,
                    zone3Minutes = 20f,
                    zone4Minutes = 32f,
                    zone5Minutes = 0f,
                    trimp = 115.6f,
                    avgHr = 134f,
                )
            coEvery { workoutRepository.getById("run-1") } returns workout
            coEvery { healthConnectRepository.readHeartRateSamples(any(), any()) } returns emptyList()
            coEvery { heartRateRepository.getByTimeRange(any(), any()) } returns emptyList()
            coEvery { dailySummaryRepository.getByDate(any()) } returns null
            coEvery { dailySummaryRepository.getSince(any()) } returns emptyList()
            coEvery {
                getWorkoutDisplayMetricsUseCase.execute(
                    workout = workout,
                    samples = any(),
                )
            } returns
                WorkoutDisplayMetrics(
                    preciseTrimp = 115.6f,
                    computedTrimp = 116,
                    trimpDisplay = "116",
                    gainedStrain = 0.37f,
                    gainedStrainDisplay = "0.37",
                    classification = null,
                )

            viewModel.loadWorkout("run-1")
            advanceUntilIdle()

            coVerify {
                heartRateRepository.getByTimeRange(
                    workout.startTime,
                    workout.endTime + 210_000L,
                )
            }
        }

    @Test
    fun `loadWorkout maps hrr1Min from sparse sample within tolerance after one minute`() =
        runTest {
            every { settingsRepository.userPreferences } returns
                MutableStateFlow(UserPreferences(hrrToleranceSeconds = 30))
            val workoutEnd = Instant.parse("2026-06-09T18:00:00Z")
            val workoutStart = workoutEnd.minusSeconds(30 * 60)
            val workout =
                WorkoutData(
                    id = "run-1",
                    startTime = workoutStart.toEpochMilli(),
                    endTime = workoutEnd.toEpochMilli(),
                    exerciseType = "running",
                    durationMinutes = 30,
                    zone1Minutes = 0f,
                    zone2Minutes = 10f,
                    zone3Minutes = 10f,
                    zone4Minutes = 10f,
                    zone5Minutes = 0f,
                    trimp = 90f,
                    avgHr = 150f,
                )
            val dbSamples =
                listOf(
                    HeartRateRecordData(
                        id = "hr-end",
                        timestampMs = workout.endTime,
                        beatsPerMinute = 170,
                        recordType = "EXERCISE",
                    ),
                    HeartRateRecordData(
                        id = "hr-80",
                        timestampMs = workout.endTime + 80_000L,
                        beatsPerMinute = 149,
                        recordType = "RECOVERY",
                    ),
                )
            coEvery { workoutRepository.getById("run-1") } returns workout
            coEvery { healthConnectRepository.readHeartRateSamples(any(), any()) } returns emptyList()
            coEvery { heartRateRepository.getByTimeRange(any(), any()) } returns dbSamples
            coEvery { dailySummaryRepository.getByDate(any()) } returns null
            coEvery { dailySummaryRepository.getSince(any()) } returns emptyList()
            coEvery {
                getWorkoutDisplayMetricsUseCase.execute(
                    workout = workout,
                    samples = any(),
                )
            } returns
                WorkoutDisplayMetrics(
                    preciseTrimp = 90f,
                    computedTrimp = 90,
                    trimpDisplay = "90",
                    gainedStrain = 0.25f,
                    gainedStrainDisplay = "0.25",
                    classification = null,
                )

            viewModel.loadWorkout("run-1")
            advanceUntilIdle()

            assertNotNull(viewModel.uiState.value.hrr1Min)
            assertEquals(21, viewModel.uiState.value.hrr1Min)
        }
}
