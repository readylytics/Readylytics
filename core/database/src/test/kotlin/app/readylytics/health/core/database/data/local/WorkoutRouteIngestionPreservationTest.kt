package app.readylytics.health.core.database.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.core.databaseschema.data.local.dao.WorkoutRoutePointDao
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRoutePointEntity
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.model.RouteState
import app.readylytics.health.core.model.domain.model.WorkoutRoutePoint
import app.readylytics.health.core.model.domain.repository.ReadOutcome
import app.readylytics.health.core.model.domain.sync.HealthIngestionBatch
import app.readylytics.health.core.model.domain.sync.PreparedWorkout
import app.readylytics.health.core.model.domain.sync.WorkoutInput
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-Room-DB coverage for workout route/GPS-summary preservation across re-ingestion, split
 * across two commit paths:
 * - The pre-existing bulk `persist(batch)` path (`RoomHealthIngestionStore.persistWorkouts`,
 *   unchanged by H5/WP-09): a routeless refetch preserves the previously stored route and
 *   GPS-summary columns via a bare-nullable coalesce.
 * - The H5/WP-09 delta path, `persistPreparedWorkouts`: the merge-against-existing semantics
 *   ([ReadOutcome.Available] replaces -- including with an authoritatively empty value --
 *   [ReadOutcome.Denied]/[ReadOutcome.Unsupported] preserves), the FK-cascaded delete an explicit
 *   `DeletionChange` still uses, and the crash-safety of the write transaction itself.
 */
@RunWith(AndroidJUnit4::class)
class WorkoutRouteIngestionPreservationTest {
    private lateinit var database: HealthDatabase
    private lateinit var store: RoomHealthIngestionStore
    private lateinit var changeStore: RoomHealthChangeIngestionStore

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    HealthDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        store = buildStore(database)
        changeStore = buildChangeStore(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun buildStore(
        db: HealthDatabase,
        workoutRoutePointDao: WorkoutRoutePointDao = db.workoutRoutePointDao(),
    ): RoomHealthIngestionStore =
        RoomHealthIngestionStore(
            daos = daosFor(db, workoutRoutePointDao),
            dailySummaryDao = db.dailySummaryDao(),
            transactionRunner = RoomTransactionRunner(db),
            vo2MaxRecordDao = db.vo2MaxRecordDao(),
        )

    private fun buildChangeStore(
        db: HealthDatabase,
        workoutRoutePointDao: WorkoutRoutePointDao = db.workoutRoutePointDao(),
    ): RoomHealthChangeIngestionStore =
        RoomHealthChangeIngestionStore(
            daos = daosFor(db, workoutRoutePointDao),
            transactionRunner = RoomTransactionRunner(db),
        )

    private fun daosFor(db: HealthDatabase, workoutRoutePointDao: WorkoutRoutePointDao): HealthRecordDaos =
        HealthRecordDaos(
            sleepSessionDao = db.sleepSessionDao(),
            sleepStageDao = db.sleepStageDao(),
            heartRateDao = db.heartRateDao(),
            hrvDao = db.hrvDao(),
            workoutDao = db.workoutDao(),
            workoutRoutePointDao = workoutRoutePointDao,
            weightRecordDao = db.weightRecordDao(),
            bodyFatRecordDao = db.bodyFatRecordDao(),
            bloodPressureRecordDao = db.bloodPressureRecordDao(),
            oxygenSaturationRecordDao = db.oxygenSaturationRecordDao(),
            bodyTemperatureRecordDao = db.bodyTemperatureRecordDao(),
            stepRecordDao = db.stepRecordDao(),
            sourceRecordDao = db.sourceRecordDao(),
            minuteBucketMaintenanceDao = db.minuteBucketMaintenanceDao(),
        )

    @Test
    fun `routeless refetch preserves stored route points and gps summary columns`() =
        runTest {
            store.persist(batch(workoutWithRoute()))

            store.persist(batch(workoutWithoutRoute()))

            val refreshed = database.workoutDao().getById(BULK_WORKOUT_ID)!!
            assertEquals(3, database.workoutRoutePointDao().getRoutePoints(BULK_WORKOUT_ID).size)
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

            val points = database.workoutRoutePointDao().getRoutePoints(BULK_WORKOUT_ID)
            assertEquals(3, points.size)
            assertTrue(points.zipWithNext().all { (a, b) -> a.timestampMs <= b.timestampMs })
        }

    @Test
    fun `routeless refetch still updates non-route workout fields`() =
        runTest {
            store.persist(batch(workoutWithRoute()))

            store.persist(batch(workoutWithoutRoute().copy(trimp = 210f, avgHr = 160f)))

            val refreshed = database.workoutDao().getById(BULK_WORKOUT_ID)!!
            assertEquals(210f, refreshed.trimp)
            assertEquals(160f, refreshed.avgHr)
        }

    @Test
    fun `route is preserved when a parent update is Denied`() =
        runTest {
            val existing = seedWorkoutWithImportedRoute()

            changeStore.persistPreparedWorkouts(
                listOf(
                    preparedWorkout(
                        existing,
                        route = ReadOutcome.Denied,
                        distanceMeters = ReadOutcome.Denied,
                        elevationMeters = ReadOutcome.Denied,
                    ),
                ),
            )

            val storedPoints = database.workoutRoutePointDao().getRoutePoints(existing.id)
            assertEquals(2, storedPoints.size)
            val refreshed = database.workoutDao().getById(existing.id)!!
            assertEquals(RouteState.IMPORTED, refreshed.routeState)
            assertEquals(500f, refreshed.totalDistanceMeters)
            assertEquals(10f, refreshed.elevationGainMeters)
        }

    @Test
    fun `Available emptyList clears a previously imported route`() =
        runTest {
            val existing = seedWorkoutWithImportedRoute()

            changeStore.persistPreparedWorkouts(
                listOf(
                    preparedWorkout(
                        existing,
                        route = ReadOutcome.Available(emptyList()),
                        distanceMeters = ReadOutcome.Available(null),
                        elevationMeters = ReadOutcome.Available(null),
                    ),
                ),
            )

            val storedPoints = database.workoutRoutePointDao().getRoutePoints(existing.id)
            assertTrue("Available(emptyList()) must authoritatively clear stored route points", storedPoints.isEmpty())
            val refreshed = database.workoutDao().getById(existing.id)!!
            assertEquals(RouteState.NOT_AVAILABLE, refreshed.routeState)
            assertEquals(null, refreshed.totalDistanceMeters)
            assertEquals(null, refreshed.avgSpeedKmh)
            assertEquals(null, refreshed.elevationGainMeters)
        }

    @Test
    fun `Available with new points replaces the stored route and derives avgSpeed`() =
        runTest {
            val existing = seedWorkoutWithImportedRoute()
            val newPoints =
                listOf(
                    WorkoutRoutePoint(workoutId = existing.id, latitude = 10.0, longitude = 20.0, timestampMs = 5_000L),
                )

            changeStore.persistPreparedWorkouts(
                listOf(
                    preparedWorkout(
                        existing,
                        route = ReadOutcome.Available(newPoints),
                        distanceMeters = ReadOutcome.Available(1_800f),
                        elevationMeters = ReadOutcome.Available(15f),
                    ),
                ),
            )

            val storedPoints = database.workoutRoutePointDao().getRoutePoints(existing.id)
            assertEquals(1, storedPoints.size)
            assertEquals(10.0, storedPoints[0].latitude, 0.0001)
            val refreshed = database.workoutDao().getById(existing.id)!!
            assertEquals(RouteState.IMPORTED, refreshed.routeState)
            assertEquals(1_800f, refreshed.totalDistanceMeters)
            assertEquals(15f, refreshed.elevationGainMeters)
            // duration is 3600s -> 1800m / 3600s * 3.6 = 1.8 km/h
            assertEquals(1.8f, refreshed.avgSpeedKmh!!, 0.001f)
        }

    @Test
    fun `explicit DeletionChange still cascades route points via the P2 dirty journal path`() =
        runTest {
            val existing = seedWorkoutWithImportedRoute()

            changeStore.deleteRecord(HealthDataType.EXERCISE, existing.id)

            assertEquals(null, database.workoutDao().getById(existing.id))
            assertTrue(database.workoutRoutePointDao().getRoutePoints(existing.id).isEmpty())
        }

    @Test
    fun `a crash mid-commit leaves the previous workout byte-for-byte unchanged`() =
        runTest {
            val existing = seedWorkoutWithImportedRoute()
            val other = seedWorkout(id = "other-workout-in-batch", startTime = 50_000L)
            val crashingStore =
                buildChangeStore(
                    database,
                    workoutRoutePointDao = AlwaysThrowingInsertDao(database.workoutRoutePointDao()),
                )

            val throwable =
                runCatching {
                    crashingStore.persistPreparedWorkouts(
                        listOf(
                            // Both parent rows are upserted in-memory before the transaction
                            // commits (first element's route is a no-op clear -- never calls
                            // insertAll)...
                            preparedWorkout(
                                existing,
                                route = ReadOutcome.Available(emptyList()),
                                distanceMeters = ReadOutcome.Available(null),
                                elevationMeters = ReadOutcome.Available(null),
                            ),
                            // ...but this second element's route application calls insertAll,
                            // which throws -- so the whole Room transaction (including the first
                            // element's already-applied parent-row write) must roll back.
                            preparedWorkout(
                                other,
                                route =
                                    ReadOutcome.Available(
                                        listOf(
                                            WorkoutRoutePoint(
                                                workoutId = other.id,
                                                latitude = 5.0,
                                                longitude = 6.0,
                                                timestampMs = 1L,
                                            ),
                                        ),
                                    ),
                                distanceMeters = ReadOutcome.Denied,
                                elevationMeters = ReadOutcome.Denied,
                            ),
                        ),
                    )
                }.exceptionOrNull()

            assertTrue("persistPreparedWorkouts should have propagated the injected failure", throwable != null)
            val refreshed = database.workoutDao().getById(existing.id)!!
            assertEquals(existing, refreshed)
            assertEquals(2, database.workoutRoutePointDao().getRoutePoints(existing.id).size)
        }

    private suspend fun seedWorkoutWithImportedRoute(): WorkoutRecordEntity {
        val existing = seedWorkout(id = "workout-with-route", startTime = 0L)
        database.workoutRoutePointDao().insertAll(
            listOf(
                WorkoutRoutePointEntity(
                    workoutId = existing.id,
                    latitude = 1.0,
                    longitude = 2.0,
                    altitude = null,
                    timestampMs = 100L,
                ),
                WorkoutRoutePointEntity(
                    workoutId = existing.id,
                    latitude = 1.1,
                    longitude = 2.1,
                    altitude = null,
                    timestampMs = 200L,
                ),
            ),
        )
        return existing
    }

    private suspend fun seedWorkout(id: String, startTime: Long): WorkoutRecordEntity {
        val entity =
            WorkoutRecordEntity(
                id = id,
                startTime = startTime,
                endTime = startTime + 3_600_000L,
                exerciseType = "RUNNING",
                durationMinutes = 60,
                zone1Minutes = 10f,
                zone2Minutes = 20f,
                zone3Minutes = 20f,
                zone4Minutes = 10f,
                zone5Minutes = 0f,
                trimp = 90f,
                avgHr = 145f,
                deviceName = "Test Watch",
                modelTrimp = 35f,
                totalDistanceMeters = 500f,
                avgSpeedKmh = 0.5f,
                elevationGainMeters = 10f,
                routeState = RouteState.IMPORTED,
            )
        database.workoutDao().upsertAll(listOf(entity))
        return entity
    }

    private fun preparedWorkout(
        existing: WorkoutRecordEntity,
        route: ReadOutcome<List<WorkoutRoutePoint>>,
        distanceMeters: ReadOutcome<Float?>,
        elevationMeters: ReadOutcome<Float?>,
    ): PreparedWorkout =
        PreparedWorkout(
            workout =
                WorkoutInput(
                    id = existing.id,
                    startTime = existing.startTime,
                    endTime = existing.endTime,
                    exerciseType = existing.exerciseType,
                    durationMinutes = existing.durationMinutes,
                    zone1Minutes = existing.zone1Minutes,
                    zone2Minutes = existing.zone2Minutes,
                    zone3Minutes = existing.zone3Minutes,
                    zone4Minutes = existing.zone4Minutes,
                    zone5Minutes = existing.zone5Minutes,
                    trimp = existing.trimp,
                    avgHr = existing.avgHr,
                    deviceName = existing.deviceName,
                ),
            route = route,
            distanceMeters = distanceMeters,
            elevationMeters = elevationMeters,
        )

    /**
     * Delegates every call to [delegate] except [insertAll], which always throws -- simulating a
     * failure partway through a multi-workout [RoomHealthChangeIngestionStore.persistPreparedWorkouts]
     * batch, so the test can assert the whole Room transaction (including any already-applied
     * writes for other workouts in the same batch) rolls back.
     */
    private class AlwaysThrowingInsertDao(private val delegate: WorkoutRoutePointDao) : WorkoutRoutePointDao {
        override suspend fun insertAll(points: List<WorkoutRoutePointEntity>): Unit =
            error("Simulated crash mid-commit")

        override suspend fun getRoutePoints(workoutId: String) = delegate.getRoutePoints(workoutId)

        override suspend fun deleteByWorkoutId(workoutId: String) = delegate.deleteByWorkoutId(workoutId)

        override suspend fun deleteForWorkouts(workoutIds: List<String>) = delegate.deleteForWorkouts(workoutIds)

        override suspend fun count() = delegate.count()

        override suspend fun deleteAll() = delegate.deleteAll()

        override suspend fun pageAfter(afterId: Long, limit: Int) = delegate.pageAfter(afterId, limit)
    }

    private fun workoutWithRoute(): WorkoutInput =
        bulkBaseWorkout().copy(
            routePoints =
                List(3) { index ->
                    WorkoutRoutePoint(
                        workoutId = BULK_WORKOUT_ID,
                        latitude = 52.50 + index * 0.001,
                        longitude = 13.40 + index * 0.001,
                        altitude = 40.0 + index,
                        timestampMs = BULK_START_MS + index * 60_000L,
                        horizontalAccuracy = 5f,
                        verticalAccuracy = 8f,
                    )
                },
            totalDistanceMeters = 1500f,
            avgSpeedKmh = 12f,
            elevationGainMeters = 40f,
            routeState = RouteState.IMPORTED,
        )

    private fun workoutWithoutRoute(): WorkoutInput = bulkBaseWorkout()

    private fun bulkBaseWorkout(): WorkoutInput =
        WorkoutInput(
            id = BULK_WORKOUT_ID,
            startTime = BULK_START_MS,
            endTime = BULK_START_MS + 3_600_000L,
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
        const val BULK_WORKOUT_ID = "workout-with-gps"
        const val BULK_START_MS = 1_785_000_000_000L
    }
}
