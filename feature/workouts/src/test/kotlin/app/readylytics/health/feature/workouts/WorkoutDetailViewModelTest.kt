package app.readylytics.health.feature.workouts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.DomainExerciseRoute
import app.readylytics.health.domain.model.DomainRoutePoint
import app.readylytics.health.domain.preferences.UserPreferencesReader
import app.readylytics.health.domain.repository.DailySummaryRepository
import app.readylytics.health.domain.repository.HealthConnectRepository
import app.readylytics.health.domain.repository.HeartRateRecordData
import app.readylytics.health.domain.repository.HeartRateRepository
import app.readylytics.health.domain.repository.PermissionStatus
import app.readylytics.health.domain.repository.RoutePoint
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
import org.junit.Assert.assertTrue
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
    fun `loadWorkout publishes detail and cached route chart data`() =
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
                    routeState = "PENDING_FOREGROUND_LOAD",
                )
            val routePoints =
                listOf(
                    RoutePoint(latitude = 60.1699, longitude = 24.9384, altitude = 10.0, timestampMs = startMs),
                    RoutePoint(
                        latitude = 60.1709,
                        longitude = 24.9394,
                        altitude = 11.0,
                        timestampMs = startMs + 60_000L,
                    ),
                    RoutePoint(
                        latitude = 60.1719,
                        longitude = 24.9404,
                        altitude = 12.0,
                        timestampMs =
                            startMs + 120_000L,
                    ),
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
            coEvery { healthConnectRepository.checkExerciseRoutePermission() } returns PermissionStatus.Granted
            coEvery { workoutRepository.getRoutePoints("run-1") } returns routePoints
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
            assertEquals(RouteDataState.Available, viewModel.uiState.value.routeUiState.state)
            assertEquals(2, viewModel.uiState.value.paceSpeedChartData.size)
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

    @Test
    fun `loadRouteDetail sets NotAvailable when routeState is NOT_AVAILABLE`() =
        runTest {
            val workout =
                WorkoutData(
                    id = "run-1",
                    startTime = 1_000_000L,
                    endTime = 2_000_000L,
                    exerciseType = "running",
                    durationMinutes = 16,
                    zone1Minutes = 0f,
                    zone2Minutes = 0f,
                    zone3Minutes = 16f,
                    zone4Minutes = 0f,
                    zone5Minutes = 0f,
                    trimp = 50f,
                    avgHr = 140f,
                    routeState = "NOT_AVAILABLE",
                )

            viewModel.loadRouteDetail(workout)
            advanceUntilIdle()

            assertEquals(RouteDataState.NotAvailable, viewModel.uiState.value.routeUiState.state)
        }

    @Test
    fun `loadRouteDetail sets PermissionRequired when route permission is missing`() =
        runTest {
            val workout =
                WorkoutData(
                    id = "run-1",
                    startTime = 1_000_000L,
                    endTime = 2_000_000L,
                    exerciseType = "running",
                    durationMinutes = 16,
                    zone1Minutes = 0f,
                    zone2Minutes = 0f,
                    zone3Minutes = 16f,
                    zone4Minutes = 0f,
                    zone5Minutes = 0f,
                    trimp = 50f,
                    avgHr = 140f,
                    routeState = "PENDING_FOREGROUND_LOAD",
                )
            coEvery { healthConnectRepository.checkExerciseRoutePermission() } returns
                PermissionStatus.Missing(
                    missing = setOf("android.permission.health.READ_EXERCISE_ROUTES"),
                )

            viewModel.loadRouteDetail(workout)
            advanceUntilIdle()

            assertEquals(RouteDataState.PermissionRequired, viewModel.uiState.value.routeUiState.state)
        }

    @Test
    fun `loadRouteDetail sets Available and projects points when DB has route points`() =
        runTest {
            val workout =
                WorkoutData(
                    id = "run-1",
                    startTime = 1_000_000L,
                    endTime = 2_000_000L,
                    exerciseType = "running",
                    durationMinutes = 16,
                    zone1Minutes = 0f,
                    zone2Minutes = 0f,
                    zone3Minutes = 16f,
                    zone4Minutes = 0f,
                    zone5Minutes = 0f,
                    trimp = 50f,
                    avgHr = 140f,
                    routeState = "IMPORTED",
                )
            val dbRoutePoints =
                listOf(
                    RoutePoint(latitude = 60.1699, longitude = 24.9384, altitude = 10.0, timestampMs = 1_000_000L),
                    RoutePoint(latitude = 60.1709, longitude = 24.9394, altitude = 12.0, timestampMs = 1_030_000L),
                    RoutePoint(latitude = 60.1719, longitude = 24.9404, altitude = 11.0, timestampMs = 1_060_000L),
                )
            coEvery { healthConnectRepository.checkExerciseRoutePermission() } returns PermissionStatus.Granted
            coEvery { workoutRepository.getRoutePoints("run-1") } returns dbRoutePoints

            viewModel.loadRouteDetail(workout)
            advanceUntilIdle()

            assertEquals(RouteDataState.Available, viewModel.uiState.value.routeUiState.state)
            assert(
                viewModel.uiState.value.routeUiState.points
                    .isNotEmpty(),
            )
            assertEquals(2, viewModel.uiState.value.paceSpeedChartData.size)
            assertTrue(
                viewModel.uiState.value.paceSpeedChartData.all { (distanceKm, paceMinKm) ->
                    distanceKm > 0f && paceMinKm > 0f
                },
            )
            assert(
                viewModel.uiState.value.elevationChartData
                    .isNotEmpty(),
            )
        }

    @Test
    fun `loadRouteDetail fetches from HealthConnect when PENDING_FOREGROUND_LOAD and saves route`() =
        runTest {
            val workout =
                WorkoutData(
                    id = "run-1",
                    startTime = 1_000_000L,
                    endTime = 4_600_000L,
                    exerciseType = "running",
                    durationMinutes = 60,
                    zone1Minutes = 0f,
                    zone2Minutes = 0f,
                    zone3Minutes = 60f,
                    zone4Minutes = 0f,
                    zone5Minutes = 0f,
                    trimp = 150f,
                    avgHr = 155f,
                    routeState = "PENDING_FOREGROUND_LOAD",
                )
            val hcRoutePoints =
                listOf(
                    DomainRoutePoint(60.1699, 24.9384, 10.0, 1_000_000L, null, null),
                    DomainRoutePoint(60.1709, 24.9394, 12.0, 1_030_000L, null, null),
                    DomainRoutePoint(60.1719, 24.9404, 11.0, 1_060_000L, null, null),
                )
            coEvery { healthConnectRepository.checkExerciseRoutePermission() } returns PermissionStatus.Granted
            coEvery { workoutRepository.getRoutePoints("run-1") } returns emptyList()
            coEvery { healthConnectRepository.readExerciseRoute("run-1") } returns
                DomainExerciseRoute(workoutId = "run-1", points = hcRoutePoints)
            coEvery { workoutRepository.saveRoutePoints(any(), any(), any()) } returns Unit

            viewModel.loadRouteDetail(workout)
            advanceUntilIdle()

            coVerify { workoutRepository.saveRoutePoints("run-1", any(), any()) }
            assertEquals(RouteDataState.Available, viewModel.uiState.value.routeUiState.state)
        }

    @Test
    fun `loadRouteDetail sets NotAvailable and updates state when HC returns no route`() =
        runTest {
            val workout =
                WorkoutData(
                    id = "run-1",
                    startTime = 1_000_000L,
                    endTime = 2_000_000L,
                    exerciseType = "running",
                    durationMinutes = 16,
                    zone1Minutes = 0f,
                    zone2Minutes = 0f,
                    zone3Minutes = 16f,
                    zone4Minutes = 0f,
                    zone5Minutes = 0f,
                    trimp = 50f,
                    avgHr = 140f,
                    routeState = "PENDING_FOREGROUND_LOAD",
                )
            coEvery { healthConnectRepository.checkExerciseRoutePermission() } returns PermissionStatus.Granted
            coEvery { workoutRepository.getRoutePoints("run-1") } returns emptyList()
            coEvery { healthConnectRepository.readExerciseRoute("run-1") } returns null
            coEvery { workoutRepository.updateRouteState(any(), any()) } returns Unit

            viewModel.loadRouteDetail(workout)
            advanceUntilIdle()

            coVerify { workoutRepository.updateRouteState("run-1", "NOT_AVAILABLE") }
            assertEquals(RouteDataState.NotAvailable, viewModel.uiState.value.routeUiState.state)
        }

    @Test
    fun `loadRouteDetail emits Error state when an exception is thrown`() =
        runTest {
            val workout =
                WorkoutData(
                    id = "run-1",
                    startTime = 1_000_000L,
                    endTime = 2_000_000L,
                    exerciseType = "running",
                    durationMinutes = 16,
                    zone1Minutes = 0f,
                    zone2Minutes = 0f,
                    zone3Minutes = 16f,
                    zone4Minutes = 0f,
                    zone5Minutes = 0f,
                    trimp = 50f,
                    avgHr = 140f,
                    routeState = "PENDING_FOREGROUND_LOAD",
                )
            coEvery { healthConnectRepository.checkExerciseRoutePermission() } throws RuntimeException("network error")

            viewModel.loadRouteDetail(workout)
            advanceUntilIdle()

            assertEquals(RouteDataState.Error, viewModel.uiState.value.routeUiState.state)
        }
}
