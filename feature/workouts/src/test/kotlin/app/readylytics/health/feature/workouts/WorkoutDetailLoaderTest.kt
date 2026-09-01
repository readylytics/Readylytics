package app.readylytics.health.feature.workouts

import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.RouteState
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.DailySummaryRepository
import app.readylytics.health.core.model.domain.repository.HeartRateRecordData
import app.readylytics.health.core.model.domain.repository.HeartRateRepository
import app.readylytics.health.core.model.domain.repository.HeartRateResolution
import app.readylytics.health.core.model.domain.repository.HeartRateSeries
import app.readylytics.health.core.model.domain.repository.WorkoutData
import app.readylytics.health.core.model.domain.repository.WorkoutRepository
import app.readylytics.health.core.model.domain.scoring.WorkoutIntensityLevel
import app.readylytics.health.core.model.domain.scoring.WorkoutLoadLevel
import app.readylytics.health.core.model.domain.sync.SyncWorkoutRouteUseCase
import app.readylytics.health.core.scoring.domain.scoring.GetWorkoutDisplayMetricsUseCase
import app.readylytics.health.core.scoring.domain.scoring.WorkoutDisplayMetrics
import app.readylytics.health.core.scoring.domain.scoring.WorkoutLoadClassification
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutDetailLoaderTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var loader: WorkoutDetailLoader

    private val workoutRepository = mockk<WorkoutRepository>()
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
        )

    private fun buildDisplayMetrics(
        preciseTrimp: Float = 60f,
        computedTrimp: Int = 60,
        gainedStrain: Float = 0.2f,
        classification: WorkoutLoadClassification? = null,
    ): WorkoutDisplayMetrics =
        WorkoutDisplayMetrics(
            preciseTrimp = preciseTrimp,
            computedTrimp = computedTrimp,
            trimpDisplay = computedTrimp.toString(),
            gainedStrain = gainedStrain,
            gainedStrainDisplay = gainedStrain.toString(),
            classification = classification,
        )

    private fun setupDefaultMocks(
        workoutId: String,
        workout: WorkoutData,
        hrSamples: List<HeartRateRecordData> = emptyList(),
        displayMetrics: WorkoutDisplayMetrics = buildDisplayMetrics(),
    ) {
        coEvery { workoutRepository.getById(workoutId) } returns workout
        coEvery { heartRateRepository.getRecoveryWindowSamples(any(), any()) } returns
            HeartRateSeries(points = hrSamples, resolution = HeartRateResolution.RAW)
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
    fun `load returns null when workout does not exist`() =
        runTest(testDispatcher) {
            coEvery { workoutRepository.getById("unknown-id") } returns null

            val result = loader.load("unknown-id", UserPreferences())

            assertNull(result)
        }

    @Test
    fun `load uses rounded load metrics from shared use case`() =
        runTest(testDispatcher) {
            val date = LocalDate.of(2026, 6, 9)
            val startMs =
                date
                    .atStartOfDay(ZoneId.systemDefault())
                    .plusHours(19)
                    .plusMinutes(28)
                    .toInstant()
                    .toEpochMilli()
            val workout =
                buildWorkout(
                    id = "run-1",
                    durationMinutes = 62,
                    trimp = 115.6f,
                    avgHr = 134f,
                    startMs = startMs,
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
            val displayMetrics =
                buildDisplayMetrics(
                    preciseTrimp = 115.6f,
                    computedTrimp = 116,
                    gainedStrain = 0.37f,
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

            setupDefaultMocks(
                workoutId = "run-1",
                workout = workout,
                hrSamples = dbSamples,
                displayMetrics = displayMetrics,
            )
            coEvery { dailySummaryRepository.getByDate(any()) } returns
                DailySummary(date = date, trimpWorkoutOnly = 115.6f, rhrBpm = 52f, totalRasWorkoutOnly = 12f)
            coEvery { dailySummaryRepository.getSince(any()) } returns
                listOf(DailySummary(date = date, trimpWorkoutOnly = 115.6f, rhrBpm = 52f, rasWorkoutOnly = 12f))

            val data = loader.load("run-1", UserPreferences())

            assertNotNull(data)
            assertEquals(116, data?.computedTrimp)
            assertEquals(0.37f, data?.gainedStrain)
            assertEquals("0.37", data?.gainedStrainDisplay)
            assertEquals(WorkoutLoadLevel.HARD, data?.classification?.finalLoad)
        }

    @Test
    fun `load requests heart rate through end plus three minutes plus tolerance`() =
        runTest(testDispatcher) {
            val toleranceSeconds = 30
            val prefs = UserPreferences(hrrToleranceSeconds = toleranceSeconds)
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
            coEvery { heartRateRepository.getRecoveryWindowSamples(any(), any()) } returns
                HeartRateSeries(points = emptyList(), resolution = HeartRateResolution.RAW)
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

            loader.load("run-1", prefs)

            coVerify {
                heartRateRepository.getRecoveryWindowSamples(
                    workout.startTime,
                    workout.endTime + 210_000L,
                )
            }
        }

    @Test
    fun `load maps hrr1Min from sparse sample within tolerance after one minute`() =
        runTest(testDispatcher) {
            val prefs = UserPreferences(hrrToleranceSeconds = 30)
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
            coEvery { heartRateRepository.getRecoveryWindowSamples(any(), any()) } returns
                HeartRateSeries(points = dbSamples, resolution = HeartRateResolution.RAW)
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

            val data = loader.load("run-1", prefs)

            assertNotNull(data?.hrr1Min)
            assertEquals(21, data?.hrr1Min)
        }

    @Test
    fun `load queries daily summary using scoringZone instead of device zone`() =
        runTest(testDispatcher) {
            val scoringZone = ZoneId.of("Pacific/Honolulu")
            val prefs = UserPreferences(scoringZoneId = scoringZone.id)

            // 2026-06-10T02:00:00Z is 2026-06-09T16:00:00-10:00 in Honolulu (date is June 9, not June 10)
            val startMs = Instant.parse("2026-06-10T02:00:00Z").toEpochMilli()
            val workout =
                buildWorkout(
                    id = "run-tz-test",
                    durationMinutes = 30,
                    startMs = startMs,
                )

            setupDefaultMocks(
                workoutId = "run-tz-test",
                workout = workout,
            )

            loader.load("run-tz-test", prefs)

            val expectedWorkoutDate = LocalDate.of(2026, 6, 9)
            val expectedMidnightMs = expectedWorkoutDate.atStartOfDay(scoringZone).toInstant().toEpochMilli()
            val expectedThirtyDaysAgoMs =
                expectedWorkoutDate
                    .minusDays(30)
                    .atStartOfDay(scoringZone)
                    .toInstant()
                    .toEpochMilli()

            coVerify { dailySummaryRepository.getByDate(expectedMidnightMs) }
            coVerify { dailySummaryRepository.getSince(expectedThirtyDaysAgoMs) }
        }
}
