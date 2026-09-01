package app.readylytics.health.feature.workouts

import app.readylytics.health.core.model.domain.model.RouteState
import app.readylytics.health.core.model.domain.model.WorkoutRoutePoint
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.DailySummaryRepository
import app.readylytics.health.core.model.domain.repository.HealthConnectRepository
import app.readylytics.health.core.model.domain.repository.HeartRateRepository
import app.readylytics.health.core.model.domain.repository.HeartRateResolution
import app.readylytics.health.core.model.domain.repository.HeartRateSeries
import app.readylytics.health.core.model.domain.repository.WorkoutData
import app.readylytics.health.core.model.domain.repository.WorkoutRepository
import app.readylytics.health.core.model.domain.sync.SyncWorkoutRouteUseCase
import app.readylytics.health.core.scoring.domain.scoring.GetWorkoutDisplayMetricsUseCase
import app.readylytics.health.core.scoring.domain.scoring.WorkoutDisplayMetrics
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutDetailLoaderRouteTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var loader: WorkoutDetailLoader

    private val workoutRepository = mockk<WorkoutRepository>()
    private val healthConnectRepository = mockk<HealthConnectRepository>(relaxed = true)
    private val heartRateRepository = mockk<HeartRateRepository>(relaxed = true)
    private val dailySummaryRepository = mockk<DailySummaryRepository>(relaxed = true)
    private val getWorkoutDisplayMetricsUseCase = mockk<GetWorkoutDisplayMetricsUseCase>()
    private val syncWorkoutRouteUseCase = mockk<SyncWorkoutRouteUseCase>(relaxed = true)

    @Before
    fun setUp() {
        coEvery { workoutRepository.getRoutePoints(any()) } returns emptyList()
        loader =
            WorkoutDetailLoader(
                workoutRepository = workoutRepository,
                hcRepo = healthConnectRepository,
                heartRateRepository = heartRateRepository,
                dailySummaryRepository = dailySummaryRepository,
                syncWorkoutRouteUseCase = syncWorkoutRouteUseCase,
                getWorkoutDisplayMetricsUseCase = getWorkoutDisplayMetricsUseCase,
                defaultDispatcher = testDispatcher,
            )
    }

    private fun buildWorkout(
        id: String,
        durationMinutes: Int = 30,
        exerciseType: String = "running",
        trimp: Float = 60f,
        avgHr: Float = 150f,
        routeState: String = RouteState.NOT_AVAILABLE,
        elevationGainMeters: Float? = null,
        startMs: Long = System.currentTimeMillis(),
    ): WorkoutData =
        WorkoutData(
            id = id,
            startTime = startMs,
            endTime = startMs + durationMinutes * 60 * 1000L,
            exerciseType = exerciseType,
            durationMinutes = durationMinutes,
            zone1Minutes = 5f,
            zone2Minutes = 10f,
            zone3Minutes = 10f,
            zone4Minutes = 5f,
            zone5Minutes = 0f,
            trimp = trimp,
            avgHr = avgHr,
            routeState = routeState,
            elevationGainMeters = elevationGainMeters,
        )

    private fun buildRoutePoints(
        workoutId: String,
        count: Int = 3,
        startMs: Long = System.currentTimeMillis(),
    ): List<WorkoutRoutePoint> =
        (0 until count).map { index ->
            WorkoutRoutePoint(
                workoutId = workoutId,
                latitude = 52.5200 + (index * 0.001),
                longitude = 13.4050 + (index * 0.001),
                altitude = 45.0 + (index * 5.0),
                timestampMs = startMs + (index * 10_000L),
            )
        }

    private fun buildDisplayMetrics(
        preciseTrimp: Float = 60f,
        computedTrimp: Int = 60,
        gainedStrain: Float = 0.2f,
    ): WorkoutDisplayMetrics =
        WorkoutDisplayMetrics(
            preciseTrimp = preciseTrimp,
            computedTrimp = computedTrimp,
            trimpDisplay = computedTrimp.toString(),
            gainedStrain = gainedStrain,
            gainedStrainDisplay = gainedStrain.toString(),
            classification = null,
        )

    private fun setupDefaultMocks(
        workoutId: String,
        workout: WorkoutData,
        routePoints: List<WorkoutRoutePoint> = emptyList(),
        displayMetrics: WorkoutDisplayMetrics = buildDisplayMetrics(),
    ) {
        coEvery { workoutRepository.getById(workoutId) } returns workout
        coEvery { workoutRepository.getRoutePoints(workoutId) } returns routePoints
        coEvery { heartRateRepository.getRecoveryWindowSamples(any(), any()) } returns
            HeartRateSeries(points = emptyList(), resolution = HeartRateResolution.RAW)
        coEvery { dailySummaryRepository.getByDate(any()) } returns null
        coEvery { dailySummaryRepository.getSince(any()) } returns emptyList()
        coEvery {
            getWorkoutDisplayMetricsUseCase.execute(
                workout = workout,
                samples = any(),
            )
        } returns displayMetrics
    }

    @Test
    fun `load with route points populates Available routeUiState and performance chart series`() =
        runTest(testDispatcher) {
            val date = LocalDate.of(2026, 6, 9)
            val startMs =
                date
                    .atStartOfDay(ZoneId.systemDefault())
                    .plusHours(10)
                    .toInstant()
                    .toEpochMilli()
            val workout =
                buildWorkout(
                    id = "run-gps",
                    routeState = RouteState.IMPORTED,
                    startMs = startMs,
                )
            val routePoints = buildRoutePoints(workoutId = "run-gps", startMs = startMs)

            setupDefaultMocks(
                workoutId = "run-gps",
                workout = workout,
                routePoints = routePoints,
            )

            val data = loader.load("run-gps", UserPreferences())

            assertNotNull(data)
            assertEquals(RouteDataState.Available, data?.routeUiState?.state)
            assertTrue(data?.routeUiState?.projectedPoints?.isNotEmpty() == true)
            assertTrue(data?.isPaceMode == true)
            assertEquals(3, data?.paceSpeedChartData?.size)
            assertEquals(3, data?.elevationChartData?.size)
            assertEquals(0.0, data?.paceSpeedChartData?.first()?.first ?: -1.0, 0.001)
            assertEquals(0.0, data?.elevationChartData?.first()?.first ?: -1.0, 0.001)
            assertEquals(45.0, data?.elevationChartData?.first()?.second ?: -1.0, 0.001)
            assertEquals(55.0, data?.elevationChartData?.last()?.second ?: -1.0, 0.001)
        }

    @Test
    fun `load excludes bogus GPS altitudes from elevation series and recomputes display gain`() =
        runTest(testDispatcher) {
            val date = LocalDate.of(2026, 6, 9)
            val startMs =
                date
                    .atStartOfDay(ZoneId.systemDefault())
                    .plusHours(10)
                    .toInstant()
                    .toEpochMilli()
            val workout =
                buildWorkout(
                    id = "run-filtered",
                    elevationGainMeters = 1_000_000f,
                    routeState = RouteState.IMPORTED,
                    startMs = startMs,
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

            setupDefaultMocks(
                workoutId = "run-filtered",
                workout = workout,
                routePoints = routePoints,
            )

            val data = loader.load("run-filtered", UserPreferences())

            assertNotNull(data)
            assertEquals(4, data?.elevationChartData?.size)
            data?.elevationChartData?.forEach { (_, alt) ->
                assertTrue("bogus altitude leaked into chart: $alt", alt <= 55.0)
            }
            assertEquals(10f, data?.displayElevationGainMeters!!, 0.001f)
        }

    @Test
    fun `load drops zero altitude placeholders when route has real terrain`() =
        runTest(testDispatcher) {
            val date = LocalDate.of(2026, 6, 9)
            val startMs =
                date
                    .atStartOfDay(ZoneId.systemDefault())
                    .plusHours(10)
                    .toInstant()
                    .toEpochMilli()
            val workout =
                buildWorkout(
                    id = "run-zeros",
                    elevationGainMeters = 1_000_000f,
                    routeState = RouteState.IMPORTED,
                    startMs = startMs,
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

            setupDefaultMocks(
                workoutId = "run-zeros",
                workout = workout,
                routePoints = routePoints,
            )

            val data = loader.load("run-zeros", UserPreferences())

            assertNotNull(data)
            assertEquals(3, data?.elevationChartData?.size)
            data?.elevationChartData?.forEach { (_, alt) ->
                assertTrue("zero placeholder leaked into chart: $alt", alt > 0.0)
            }
            assertEquals(5f, data?.displayElevationGainMeters!!, 0.001f)
        }

    @Test
    fun `load with cycling activity sets isPaceMode false and computes speed series`() =
        runTest(testDispatcher) {
            val date = LocalDate.of(2026, 6, 9)
            val startMs =
                date
                    .atStartOfDay(ZoneId.systemDefault())
                    .plusHours(10)
                    .toInstant()
                    .toEpochMilli()
            val workout =
                buildWorkout(
                    id = "ride-1",
                    exerciseType = "cycling",
                    durationMinutes = 60,
                    trimp = 80f,
                    avgHr = 140f,
                    routeState = RouteState.IMPORTED,
                    startMs = startMs,
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

            setupDefaultMocks(
                workoutId = "ride-1",
                workout = workout,
                routePoints = routePoints,
                displayMetrics =
                    WorkoutDisplayMetrics(
                        preciseTrimp = 80f,
                        computedTrimp = 80,
                        trimpDisplay = "80",
                        gainedStrain = 0.3f,
                        gainedStrainDisplay = "0.3",
                        classification = null,
                    ),
            )

            val data = loader.load("ride-1", UserPreferences())

            assertNotNull(data)
            assertEquals(RouteDataState.Available, data?.routeUiState?.state)
            assertFalse(data?.isPaceMode == true)
            assertEquals(2, data?.paceSpeedChartData?.size)
            assertTrue((data?.paceSpeedChartData?.first()?.second ?: 0.0) > 0.0)
        }

    @Test
    fun `load with PERMISSION_REQUIRED sets RouteDataState PermissionRequired`() =
        runTest(testDispatcher) {
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

            setupDefaultMocks(
                workoutId = "run-perm",
                workout = workout,
            )

            val data = loader.load("run-perm", UserPreferences())

            assertNotNull(data)
            assertEquals(RouteDataState.PermissionRequired, data?.routeUiState?.state)
            assertTrue(data?.paceSpeedChartData?.isEmpty() == true)
            assertTrue(data?.elevationChartData?.isEmpty() == true)
        }

    @Test
    fun `load auto-syncs route if permission is already granted when routeState is PERMISSION_REQUIRED`() =
        runTest(testDispatcher) {
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
            setupDefaultMocks(
                workoutId = "run-auto-sync",
                workout = workout,
            )

            loader.load("run-auto-sync", UserPreferences())

            coVerify(exactly = 1) { syncWorkoutRouteUseCase.invoke("run-auto-sync") }
        }

    @Test
    fun `load formats chart data with bounded x precision`() =
        runTest(testDispatcher) {
            val date = LocalDate.of(2026, 6, 9)
            val startMs =
                date
                    .atStartOfDay(ZoneId.systemDefault())
                    .plusHours(10)
                    .toInstant()
                    .toEpochMilli()
            val workout =
                buildWorkout(
                    id = "run-precision",
                    routeState = RouteState.IMPORTED,
                    startMs = startMs,
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

            setupDefaultMocks(
                workoutId = "run-precision",
                workout = workout,
                routePoints = routePoints,
            )

            val data = loader.load("run-precision", UserPreferences())

            assertNotNull(data)
            assertTrue(data?.paceSpeedChartData?.isNotEmpty() == true)
            assertTrue(data?.elevationChartData?.isNotEmpty() == true)
            data?.paceSpeedChartData?.forEach { (x, _) ->
                val decimals = x.toString().substringAfter(".", "").length
                assertTrue("x-value $x should have <= 4 decimal places", decimals <= 4)
            }
            data?.elevationChartData?.forEach { (x, _) ->
                val decimals = x.toString().substringAfter(".", "").length
                assertTrue("x-value $x should have <= 4 decimal places", decimals <= 4)
            }
        }
}
