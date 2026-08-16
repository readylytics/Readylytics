package app.readylytics.health.feature.workouts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.RouteState
import app.readylytics.health.domain.model.WorkoutRoutePoint
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
import app.readylytics.health.domain.sync.SyncWorkoutRouteUseCase
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
import org.junit.Assert.assertFalse
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
    private val syncWorkoutRouteUseCase = mockk<SyncWorkoutRouteUseCase>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        coEvery { workoutRepository.getRoutePoints(any()) } returns emptyList()
        viewModel =
            WorkoutDetailViewModel(
                workoutRepository = workoutRepository,
                hcRepo = healthConnectRepository,
                heartRateRepository = heartRateRepository,
                dailySummaryRepository = dailySummaryRepository,
                settingsRepo = settingsRepository,
                getWorkoutDisplayMetricsUseCase = getWorkoutDisplayMetricsUseCase,
                syncWorkoutRouteUseCase = syncWorkoutRouteUseCase,
                savedStateHandle = SavedStateHandle(),
                defaultDispatcher = testDispatcher,
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
                    syncWorkoutRouteUseCase = syncWorkoutRouteUseCase,
                    savedStateHandle = restoredHandle,
                    defaultDispatcher = testDispatcher,
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
    fun `loadWorkout with route points populates Available routeUiState and performance chart series`() =
        runTest {
            val date = LocalDate.of(2026, 6, 9)
            val startMs =
                date
                    .atStartOfDay(ZoneId.systemDefault())
                    .plusHours(10)
                    .toInstant()
                    .toEpochMilli()
            val workout =
                WorkoutData(
                    id = "run-gps",
                    startTime = startMs,
                    endTime = startMs + 30 * 60 * 1000L,
                    exerciseType = "running",
                    durationMinutes = 30,
                    zone1Minutes = 5f,
                    zone2Minutes = 10f,
                    zone3Minutes = 10f,
                    zone4Minutes = 5f,
                    zone5Minutes = 0f,
                    trimp = 60f,
                    avgHr = 150f,
                    routeState = RouteState.IMPORTED,
                )
            val routePoints =
                listOf(
                    WorkoutRoutePoint(
                        workoutId = "run-gps",
                        latitude = 52.5200,
                        longitude = 13.4050,
                        altitude = 45.0,
                        timestampMs = startMs,
                    ),
                    WorkoutRoutePoint(
                        workoutId = "run-gps",
                        latitude = 52.5210,
                        longitude = 13.4060,
                        altitude = 50.0,
                        timestampMs = startMs + 10_000L,
                    ),
                    WorkoutRoutePoint(
                        workoutId = "run-gps",
                        latitude = 52.5220,
                        longitude = 13.4070,
                        altitude = 55.0,
                        timestampMs = startMs + 20_000L,
                    ),
                )

            coEvery { workoutRepository.getById("run-gps") } returns workout
            coEvery { workoutRepository.getRoutePoints("run-gps") } returns routePoints
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
                    preciseTrimp = 60f,
                    computedTrimp = 60,
                    trimpDisplay = "60",
                    gainedStrain = 0.2f,
                    gainedStrainDisplay = "0.2",
                    classification = null,
                )

            viewModel.loadWorkout("run-gps")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(RouteDataState.Available, state.routeUiState.state)
            assertTrue(state.routeUiState.projectedPoints.isNotEmpty())
            assertTrue(state.isPaceMode)
            assertEquals(3, state.paceSpeedChartData.size)
            assertEquals(3, state.elevationChartData.size)
            assertEquals(0.0, state.paceSpeedChartData.first().first, 0.001)
            assertEquals(0.0, state.elevationChartData.first().first, 0.001)
            assertEquals(45.0, state.elevationChartData.first().second, 0.001)
            assertEquals(55.0, state.elevationChartData.last().second, 0.001)
        }

    @Test
    fun `loadWorkout excludes bogus GPS altitudes from elevation series and recomputes display gain`() =
        runTest {
            val date = LocalDate.of(2026, 6, 9)
            val startMs =
                date
                    .atStartOfDay(ZoneId.systemDefault())
                    .plusHours(10)
                    .toInstant()
                    .toEpochMilli()
            val workout =
                WorkoutData(
                    id = "run-filtered",
                    startTime = startMs,
                    endTime = startMs + 30 * 60 * 1000L,
                    exerciseType = "running",
                    durationMinutes = 30,
                    zone1Minutes = 5f,
                    zone2Minutes = 10f,
                    zone3Minutes = 10f,
                    zone4Minutes = 5f,
                    zone5Minutes = 0f,
                    trimp = 60f,
                    avgHr = 150f,
                    elevationGainMeters = 1_000_000f,
                    routeState = RouteState.IMPORTED,
                )
            val routePoints =
                listOf(
                    WorkoutRoutePoint(
                        workoutId = "run-filtered",
                        latitude = 52.5200,
                        longitude = 13.4050,
                        altitude = 45.0,
                        timestampMs = startMs,
                    ),
                    WorkoutRoutePoint(
                        workoutId = "run-filtered",
                        latitude = 52.5210,
                        longitude = 13.4060,
                        altitude = 50.0,
                        timestampMs = startMs + 10_000L,
                    ),
                    WorkoutRoutePoint(
                        workoutId = "run-filtered",
                        latitude = 52.5220,
                        longitude = 13.4070,
                        altitude = 1_000_000.0,
                        timestampMs = startMs + 20_000L,
                    ),
                    WorkoutRoutePoint(
                        workoutId = "run-filtered",
                        latitude = 52.5230,
                        longitude = 13.4080,
                        altitude = 55.0,
                        timestampMs = startMs + 30_000L,
                    ),
                )

            coEvery { workoutRepository.getById("run-filtered") } returns workout
            coEvery { workoutRepository.getRoutePoints("run-filtered") } returns routePoints
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
                    preciseTrimp = 60f,
                    computedTrimp = 60,
                    trimpDisplay = "60",
                    gainedStrain = 0.2f,
                    gainedStrainDisplay = "0.2",
                    classification = null,
                )

            viewModel.loadWorkout("run-filtered")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(4, state.elevationChartData.size)
            state.elevationChartData.forEach { (_, alt) ->
                assertTrue("bogus altitude leaked into chart: $alt", alt <= 55.0)
            }
            assertEquals(10f, state.displayElevationGainMeters!!, 0.001f)
        }

    @Test
    fun `loadWorkout drops zero altitude placeholders when route has real terrain`() =
        runTest {
            val date = LocalDate.of(2026, 6, 9)
            val startMs =
                date
                    .atStartOfDay(ZoneId.systemDefault())
                    .plusHours(10)
                    .toInstant()
                    .toEpochMilli()
            val workout =
                WorkoutData(
                    id = "run-zeros",
                    startTime = startMs,
                    endTime = startMs + 30 * 60 * 1000L,
                    exerciseType = "running",
                    durationMinutes = 30,
                    zone1Minutes = 5f,
                    zone2Minutes = 10f,
                    zone3Minutes = 10f,
                    zone4Minutes = 5f,
                    zone5Minutes = 0f,
                    trimp = 60f,
                    avgHr = 150f,
                    elevationGainMeters = 1_000_000f,
                    routeState = RouteState.IMPORTED,
                )
            val routePoints =
                listOf(
                    WorkoutRoutePoint(
                        workoutId = "run-zeros",
                        latitude = 52.5200,
                        longitude = 13.4050,
                        altitude = 0.0,
                        timestampMs = startMs,
                    ),
                    WorkoutRoutePoint(
                        workoutId = "run-zeros",
                        latitude = 52.5210,
                        longitude = 13.4060,
                        altitude = 270.0,
                        timestampMs = startMs + 10_000L,
                    ),
                    WorkoutRoutePoint(
                        workoutId = "run-zeros",
                        latitude = 52.5220,
                        longitude = 13.4070,
                        altitude = 0.0,
                        timestampMs = startMs + 20_000L,
                    ),
                    WorkoutRoutePoint(
                        workoutId = "run-zeros",
                        latitude = 52.5230,
                        longitude = 13.4080,
                        altitude = 275.0,
                        timestampMs = startMs + 30_000L,
                    ),
                )

            coEvery { workoutRepository.getById("run-zeros") } returns workout
            coEvery { workoutRepository.getRoutePoints("run-zeros") } returns routePoints
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
                    preciseTrimp = 60f,
                    computedTrimp = 60,
                    trimpDisplay = "60",
                    gainedStrain = 0.2f,
                    gainedStrainDisplay = "0.2",
                    classification = null,
                )

            viewModel.loadWorkout("run-zeros")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(3, state.elevationChartData.size)
            state.elevationChartData.forEach { (_, alt) ->
                assertTrue("zero placeholder leaked into chart: $alt", alt > 0.0)
            }
            assertEquals(5f, state.displayElevationGainMeters!!, 0.001f)
        }

    @Test
    fun `loadWorkout with cycling activity sets isPaceMode false and computes speed series`() =
        runTest {
            val date = LocalDate.of(2026, 6, 9)
            val startMs =
                date
                    .atStartOfDay(ZoneId.systemDefault())
                    .plusHours(10)
                    .toInstant()
                    .toEpochMilli()
            val workout =
                WorkoutData(
                    id = "ride-1",
                    startTime = startMs,
                    endTime = startMs + 60 * 60 * 1000L,
                    exerciseType = "cycling",
                    durationMinutes = 60,
                    zone1Minutes = 10f,
                    zone2Minutes = 20f,
                    zone3Minutes = 20f,
                    zone4Minutes = 10f,
                    zone5Minutes = 0f,
                    trimp = 80f,
                    avgHr = 140f,
                    routeState = RouteState.IMPORTED,
                )
            val routePoints =
                listOf(
                    WorkoutRoutePoint(
                        workoutId = "ride-1",
                        latitude = 52.5200,
                        longitude = 13.4050,
                        altitude = 40.0,
                        timestampMs = startMs,
                    ),
                    WorkoutRoutePoint(
                        workoutId = "ride-1",
                        latitude = 52.5300,
                        longitude = 13.4150,
                        altitude = 45.0,
                        timestampMs = startMs + 120_000L,
                    ),
                )

            coEvery { workoutRepository.getById("ride-1") } returns workout
            coEvery { workoutRepository.getRoutePoints("ride-1") } returns routePoints
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
                    preciseTrimp = 80f,
                    computedTrimp = 80,
                    trimpDisplay = "80",
                    gainedStrain = 0.3f,
                    gainedStrainDisplay = "0.3",
                    classification = null,
                )

            viewModel.loadWorkout("ride-1")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(RouteDataState.Available, state.routeUiState.state)
            assertFalse(state.isPaceMode)
            assertEquals(2, state.paceSpeedChartData.size)
            assertTrue(state.paceSpeedChartData.first().second > 0.0)
        }

    @Test
    fun `loadWorkout with PERMISSION_REQUIRED sets RouteDataState PermissionRequired`() =
        runTest {
            val date = LocalDate.of(2026, 6, 9)
            val startMs =
                date
                    .atStartOfDay(ZoneId.systemDefault())
                    .plusHours(10)
                    .toInstant()
                    .toEpochMilli()
            val workout =
                WorkoutData(
                    id = "run-perm",
                    startTime = startMs,
                    endTime = startMs + 30 * 60 * 1000L,
                    exerciseType = "running",
                    durationMinutes = 30,
                    zone1Minutes = 5f,
                    zone2Minutes = 10f,
                    zone3Minutes = 10f,
                    zone4Minutes = 5f,
                    zone5Minutes = 0f,
                    trimp = 60f,
                    avgHr = 150f,
                    routeState = RouteState.PERMISSION_REQUIRED,
                )

            coEvery { workoutRepository.getById("run-perm") } returns workout
            coEvery { workoutRepository.getRoutePoints("run-perm") } returns emptyList()
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
                    preciseTrimp = 60f,
                    computedTrimp = 60,
                    trimpDisplay = "60",
                    gainedStrain = 0.2f,
                    gainedStrainDisplay = "0.2",
                    classification = null,
                )

            viewModel.loadWorkout("run-perm")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(RouteDataState.PermissionRequired, state.routeUiState.state)
            assertTrue(state.paceSpeedChartData.isEmpty())
            assertTrue(state.elevationChartData.isEmpty())
        }

    @Test
    fun `loadWorkout with no route sets RouteDataState NotAvailable`() =
        runTest {
            val date = LocalDate.of(2026, 6, 9)
            val startMs =
                date
                    .atStartOfDay(ZoneId.systemDefault())
                    .plusHours(10)
                    .toInstant()
                    .toEpochMilli()
            val workout =
                WorkoutData(
                    id = "run-no-route",
                    startTime = startMs,
                    endTime = startMs + 30 * 60 * 1000L,
                    exerciseType = "running",
                    durationMinutes = 30,
                    zone1Minutes = 5f,
                    zone2Minutes = 10f,
                    zone3Minutes = 10f,
                    zone4Minutes = 5f,
                    zone5Minutes = 0f,
                    trimp = 60f,
                    avgHr = 150f,
                    routeState = RouteState.NOT_AVAILABLE,
                )

            coEvery { workoutRepository.getById("run-no-route") } returns workout
            coEvery { workoutRepository.getRoutePoints("run-no-route") } returns emptyList()
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
                    preciseTrimp = 60f,
                    computedTrimp = 60,
                    trimpDisplay = "60",
                    gainedStrain = 0.2f,
                    gainedStrainDisplay = "0.2",
                    classification = null,
                )

            viewModel.loadWorkout("run-no-route")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(RouteDataState.NotAvailable, state.routeUiState.state)
            assertTrue(state.paceSpeedChartData.isEmpty())
            assertTrue(state.elevationChartData.isEmpty())
        }

    @Test
    fun `onRoutePermissionResult triggers syncWorkoutRouteUseCase and reloads workout`() =
        runTest {
            val date = LocalDate.of(2026, 6, 9)
            val startMs =
                date
                    .atStartOfDay(ZoneId.systemDefault())
                    .plusHours(10)
                    .toInstant()
                    .toEpochMilli()
            val workout =
                WorkoutData(
                    id = "run-perm-test",
                    startTime = startMs,
                    endTime = startMs + 30 * 60 * 1000L,
                    exerciseType = "running",
                    durationMinutes = 30,
                    zone1Minutes = 5f,
                    zone2Minutes = 10f,
                    zone3Minutes = 10f,
                    zone4Minutes = 5f,
                    zone5Minutes = 0f,
                    trimp = 60f,
                    avgHr = 150f,
                    routeState = RouteState.PERMISSION_REQUIRED,
                )

            coEvery { workoutRepository.getById("run-perm-test") } returns workout
            coEvery { workoutRepository.getRoutePoints("run-perm-test") } returns emptyList()
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
                    preciseTrimp = 60f,
                    computedTrimp = 60,
                    trimpDisplay = "60",
                    gainedStrain = 0.2f,
                    gainedStrainDisplay = "0.2",
                    classification = null,
                )

            viewModel.loadWorkout("run-perm-test")
            advanceUntilIdle()

            assertEquals(RouteDataState.PermissionRequired, viewModel.uiState.value.routeUiState.state)

            viewModel.onRoutePermissionResult()
            advanceUntilIdle()

            coVerify(atLeast = 1) { syncWorkoutRouteUseCase.invoke("run-perm-test") }
        }

    @Test
    fun `loadWorkout auto-syncs route if permission is already granted when routeState is PERMISSION_REQUIRED`() =
        runTest {
            val date = LocalDate.of(2026, 6, 9)
            val startMs =
                date
                    .atStartOfDay(ZoneId.systemDefault())
                    .plusHours(10)
                    .toInstant()
                    .toEpochMilli()
            val workout =
                WorkoutData(
                    id = "run-auto-sync",
                    startTime = startMs,
                    endTime = startMs + 30 * 60 * 1000L,
                    exerciseType = "running",
                    durationMinutes = 30,
                    zone1Minutes = 5f,
                    zone2Minutes = 10f,
                    zone3Minutes = 10f,
                    zone4Minutes = 5f,
                    zone5Minutes = 0f,
                    trimp = 60f,
                    avgHr = 150f,
                    routeState = RouteState.PERMISSION_REQUIRED,
                )

            coEvery { healthConnectRepository.hasExerciseRoutesPermission() } returns true
            coEvery { workoutRepository.getById("run-auto-sync") } returns workout
            coEvery { workoutRepository.getRoutePoints("run-auto-sync") } returns emptyList()
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
                    preciseTrimp = 60f,
                    computedTrimp = 60,
                    trimpDisplay = "60",
                    gainedStrain = 0.2f,
                    gainedStrainDisplay = "0.2",
                    classification = null,
                )

            viewModel.loadWorkout("run-auto-sync")
            advanceUntilIdle()

            coVerify(exactly = 1) { syncWorkoutRouteUseCase.invoke("run-auto-sync") }
        }

    @Test
    fun `loadWorkout formats chart data with bounded x precision`() =
        runTest {
            val date = LocalDate.of(2026, 6, 9)
            val startMs =
                date
                    .atStartOfDay(ZoneId.systemDefault())
                    .plusHours(10)
                    .toInstant()
                    .toEpochMilli()
            val workout =
                WorkoutData(
                    id = "run-precision",
                    startTime = startMs,
                    endTime = startMs + 30 * 60 * 1000L,
                    exerciseType = "running",
                    durationMinutes = 30,
                    zone1Minutes = 5f,
                    zone2Minutes = 10f,
                    zone3Minutes = 10f,
                    zone4Minutes = 5f,
                    zone5Minutes = 0f,
                    trimp = 60f,
                    avgHr = 150f,
                    routeState = RouteState.IMPORTED,
                )
            val routePoints =
                listOf(
                    WorkoutRoutePoint(
                        workoutId = "run-precision",
                        timestampMs = startMs,
                        latitude = 37.7749295,
                        longitude = -122.4194155,
                        altitude = 12.3456,
                    ),
                    WorkoutRoutePoint(
                        workoutId = "run-precision",
                        timestampMs = startMs + 10000,
                        latitude = 37.7750123,
                        longitude = -122.4195789,
                        altitude = 15.6789,
                    ),
                    WorkoutRoutePoint(
                        workoutId = "run-precision",
                        timestampMs = startMs + 20000,
                        latitude = 37.7751987,
                        longitude = -122.4196123,
                        altitude = 18.9123,
                    ),
                )

            coEvery { workoutRepository.getById("run-precision") } returns workout
            coEvery { workoutRepository.getRoutePoints("run-precision") } returns routePoints
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
                    preciseTrimp = 60f,
                    computedTrimp = 60,
                    trimpDisplay = "60",
                    gainedStrain = 0.2f,
                    gainedStrainDisplay = "0.2",
                    classification = null,
                )

            viewModel.loadWorkout("run-precision")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state.paceSpeedChartData.isNotEmpty())
            assertTrue(state.elevationChartData.isNotEmpty())
            state.paceSpeedChartData.forEach { (x, _) ->
                val decimals = x.toString().substringAfter(".", "").length
                assertTrue("x-value $x should have <= 4 decimal places", decimals <= 4)
            }
            state.elevationChartData.forEach { (x, _) ->
                val decimals = x.toString().substringAfter(".", "").length
                assertTrue("x-value $x should have <= 4 decimal places", decimals <= 4)
            }
        }
}
