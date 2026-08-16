package app.readylytics.health.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.domain.model.RouteState
import app.readylytics.health.domain.model.WorkoutRoutePoint
import app.readylytics.health.domain.sync.HealthIngestionBatch
import app.readylytics.health.domain.sync.WorkoutInput
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A sync pass that fails to read a route (transient IO/RemoteException, revoked route consent)
 * reports NOT_AVAILABLE with no points. Ingestion must leave the previously stored route intact --
 * see the idempotency contract in internal-docs/DATA_FLOW.md.
 */
@RunWith(AndroidJUnit4::class)
class WorkoutRouteIngestionPreservationTest {
    private lateinit var database: HealthDatabase
    private lateinit var store: RoomHealthIngestionStore

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    HealthDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        store =
            RoomHealthIngestionStore(
                sleepSessionDao = database.sleepSessionDao(),
                sleepStageDao = database.sleepStageDao(),
                heartRateDao = database.heartRateDao(),
                hrvDao = database.hrvDao(),
                workoutDao = database.workoutDao(),
                workoutRoutePointDao = database.workoutRoutePointDao(),
                weightRecordDao = database.weightRecordDao(),
                bodyFatRecordDao = database.bodyFatRecordDao(),
                bloodPressureRecordDao = database.bloodPressureRecordDao(),
                oxygenSaturationRecordDao = database.oxygenSaturationRecordDao(),
                bodyTemperatureRecordDao = database.bodyTemperatureRecordDao(),
                stepRecordDao = database.stepRecordDao(),
                dailySummaryDao = database.dailySummaryDao(),
                sourceRecordDao = database.sourceRecordDao(),
                transactionRunner = RoomTransactionRunner(database),
            )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `routeless refetch preserves stored route points and gps summary columns`() =
        runTest {
            store.persist(batch(workoutWithRoute()))

            store.persist(batch(workoutWithoutRoute()))

            val refreshed = database.workoutDao().getById(WORKOUT_ID)!!
            assertEquals(3, database.workoutRoutePointDao().getRoutePoints(WORKOUT_ID).size)
            assertEquals(1500f, refreshed.totalDistanceMeters)
            assertEquals(12f, refreshed.avgSpeedKmh)
            assertEquals(40f, refreshed.elevationGainMeters)
            assertEquals(RouteState.IMPORTED, refreshed.routeState)
        }

    @Test
    fun `refetch that carries a route replaces the stored points without duplicating them`() =
        runTest {
            store.persist(batch(workoutWithRoute()))

            store.persist(batch(workoutWithRoute()))

            val points = database.workoutRoutePointDao().getRoutePoints(WORKOUT_ID)
            assertEquals(3, points.size)
            assertTrue(points.zipWithNext().all { (a, b) -> a.timestampMs <= b.timestampMs })
        }

    @Test
    fun `routeless refetch still updates non-route workout fields`() =
        runTest {
            store.persist(batch(workoutWithRoute()))

            store.persist(batch(workoutWithoutRoute().copy(trimp = 210f, avgHr = 160f)))

            val refreshed = database.workoutDao().getById(WORKOUT_ID)!!
            assertEquals(210f, refreshed.trimp)
            assertEquals(160f, refreshed.avgHr)
        }

    private fun workoutWithRoute(): WorkoutInput =
        baseWorkout().copy(
            routePoints =
                List(3) { index ->
                    WorkoutRoutePoint(
                        workoutId = WORKOUT_ID,
                        latitude = 52.50 + index * 0.001,
                        longitude = 13.40 + index * 0.001,
                        altitude = 40.0 + index,
                        timestampMs = START_MS + index * 60_000L,
                        horizontalAccuracy = 5f,
                        verticalAccuracy = 8f,
                    )
                },
            totalDistanceMeters = 1500f,
            avgSpeedKmh = 12f,
            elevationGainMeters = 40f,
            routeState = RouteState.IMPORTED,
        )

    private fun workoutWithoutRoute(): WorkoutInput = baseWorkout()

    private fun baseWorkout(): WorkoutInput =
        WorkoutInput(
            id = WORKOUT_ID,
            startTime = START_MS,
            endTime = START_MS + 3_600_000L,
            exerciseType = "56",
            durationMinutes = 60,
            zone1Minutes = 10f,
            zone2Minutes = 20f,
            zone3Minutes = 20f,
            zone4Minutes = 10f,
            zone5Minutes = 0f,
            trimp = 120f,
            avgHr = 145f,
            deviceName = "Test Watch",
        )

    private fun batch(workout: WorkoutInput): HealthIngestionBatch =
        HealthIngestionBatch(
            sleepSessions = emptyList(),
            sleepStages = emptyList(),
            heartRateSamples = emptyList(),
            hrvSamples = emptyList(),
            workouts = listOf(workout),
            weights = emptyList(),
            bodyFatSamples = emptyList(),
            bloodPressureSamples = emptyList(),
            oxygenSaturationSamples = emptyList(),
            bodyTemperatureSamples = emptyList(),
            stepRecords = emptyList(),
        )

    private companion object {
        const val WORKOUT_ID = "workout-with-gps"
        const val START_MS = 1_785_000_000_000L
    }
}
