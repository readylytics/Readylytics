package app.readylytics.health.data.repository

import androidx.room.withTransaction
import app.readylytics.health.data.local.HealthDatabase
import app.readylytics.health.data.local.dao.WorkoutDao
import app.readylytics.health.data.local.dao.WorkoutRoutePointDao
import app.readylytics.health.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.data.local.entity.WorkoutRoutePointEntity
import app.readylytics.health.domain.model.TimestampedTrimp
import app.readylytics.health.domain.repository.RoutePoint
import app.readylytics.health.domain.repository.WorkoutStats
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WorkoutRepositoryImplTest {
    private val workoutDao = FakeWorkoutDao()
    private val routePointDao = FakeWorkoutRoutePointDao()
    private val database = mockk<HealthDatabase>(relaxed = true)
    private val repository = WorkoutRepositoryImpl(workoutDao, routePointDao, database)

    @Before
    fun setUp() {
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery {
            database.withTransaction(any<suspend () -> Any>())
        } answers {
            val block = secondArg<suspend () -> Any>()
            kotlinx.coroutines.runBlocking { block() }
        }
    }

    @Test
    fun getRoutePointsMapsEntitiesToDomainModel() = runTest {
        val workoutId = "workout-1"
        routePointDao.insertAll(
            listOf(
                WorkoutRoutePointEntity(
                    id = 1L,
                    workoutId = workoutId,
                    latitude = 37.7749,
                    longitude = -122.4194,
                    altitude = 10.0,
                    timestampMs = 1000L,
                    horizontalAccuracy = null,
                    verticalAccuracy = null
                ),
                WorkoutRoutePointEntity(
                    id = 2L,
                    workoutId = workoutId,
                    latitude = 37.7750,
                    longitude = -122.4195,
                    altitude = 11.0,
                    timestampMs = 2000L,
                    horizontalAccuracy = null,
                    verticalAccuracy = null
                )
            )
        )

        val result = repository.getRoutePoints(workoutId)

        assertEquals(2, result.size)
        assertEquals(RoutePoint(37.7749, -122.4194, 10.0, 1000L), result[0])
        assertEquals(RoutePoint(37.7750, -122.4195, 11.0, 2000L), result[1])
    }

    @Test
    fun updateRouteStateUpdatesStateCorrectively() = runTest {
        val workoutId = "workout-2"
        val initialWorkout = createWorkoutRecord(workoutId).copy(routeState = "NOT_AVAILABLE")
        workoutDao.upsertAll(listOf(initialWorkout))

        repository.updateRouteState(workoutId, "SYNCING")

        val updated = workoutDao.getById(workoutId)
        assertNotNull(updated)
        assertEquals("SYNCING", updated.routeState)
    }

    @Test
    fun updateRouteStateDoesNothingIfWorkoutNotFound() = runTest {
        repository.updateRouteState("nonexistent-workout", "SYNCING")
        // Should return without throwing error
    }

    @Test
    fun saveRoutePointsDeletesOldPointsSavesNewPointsAndUpdatesWorkoutWithImportedStateAndStats() = runTest {
        val workoutId = "workout-3"
        val initialWorkout = createWorkoutRecord(workoutId).copy(
            routeState = "NOT_AVAILABLE",
            avgSpeedKmh = null,
            avgPaceMinKm = null,
            elevationGainMeters = null,
            totalDistanceMeters = null
        )
        workoutDao.upsertAll(listOf(initialWorkout))

        // Pre-populate with a route point that should be deleted
        routePointDao.insertAll(
            listOf(
                WorkoutRoutePointEntity(
                    id = 99L,
                    workoutId = workoutId,
                    latitude = 1.0,
                    longitude = 2.0,
                    altitude = 3.0,
                    timestampMs = 500L,
                    horizontalAccuracy = null,
                    verticalAccuracy = null
                )
            )
        )

        val newPoints = listOf(
            RoutePoint(10.0, 20.0, 30.0, 1500L),
            RoutePoint(40.0, 50.0, 60.0, 2500L)
        )
        val stats = WorkoutStats(
            avgSpeedKmh = 12.5f,
            avgPaceMinKm = 4.8f,
            elevationGainMeters = 150f,
            totalDistanceMeters = 5000f
        )

        repository.saveRoutePoints(workoutId, newPoints, stats)

        // Verify route points database table has only the new points
        val savedPoints = routePointDao.getRoutePoints(workoutId)
        assertEquals(2, savedPoints.size)
        assertEquals(10.0, savedPoints[0].latitude)
        assertEquals(40.0, savedPoints[1].latitude)

        // Verify workout record has updated routeState and stats
        val updatedWorkout = workoutDao.getById(workoutId)
        assertNotNull(updatedWorkout)
        assertEquals("IMPORTED", updatedWorkout.routeState)
        assertEquals(12.5f, updatedWorkout.avgSpeedKmh)
        assertEquals(4.8f, updatedWorkout.avgPaceMinKm)
        assertEquals(150f, updatedWorkout.elevationGainMeters)
        assertEquals(5000f, updatedWorkout.totalDistanceMeters)
    }

    @Test
    fun saveRoutePointsDoesNothingIfWorkoutNotFound() = runTest {
        val workoutId = "nonexistent-workout"
        val newPoints = listOf(
            RoutePoint(10.0, 20.0, 30.0, 1500L)
        )
        val stats = WorkoutStats(
            avgSpeedKmh = 12.5f,
            avgPaceMinKm = 4.8f,
            elevationGainMeters = 150f,
            totalDistanceMeters = 5000f
        )

        repository.saveRoutePoints(workoutId, newPoints, stats)

        // Verify route points are not saved because workout is not found
        val savedPoints = routePointDao.getRoutePoints(workoutId)
        assertEquals(0, savedPoints.size)
    }

    private fun createWorkoutRecord(id: String) = WorkoutRecordEntity(
        id = id,
        startTime = 10000L,
        endTime = 12000L,
        exerciseType = "RUNNING",
        durationMinutes = 33,
        zone1Minutes = 1.0f,
        zone2Minutes = 2.0f,
        zone3Minutes = 3.0f,
        zone4Minutes = 4.0f,
        zone5Minutes = 5.0f,
        trimp = 50.0f,
        avgHr = 150.0f,
        deviceName = "Garmin Fenix"
    )

    private class FakeWorkoutDao : WorkoutDao {
        private val records = mutableMapOf<String, WorkoutRecordEntity>()

        override suspend fun getById(id: String): WorkoutRecordEntity? = records[id]

        override suspend fun upsertAll(records: List<WorkoutRecordEntity>) {
            records.forEach { this.records[it.id] = it }
        }

        override fun _observeSince(fromMs: Long): Flow<List<WorkoutRecordEntity>> = TODO()
        override suspend fun getPaged(fromMs: Long, limit: Int, offset: Int): List<WorkoutRecordEntity> = TODO()
        override suspend fun getSince(fromMs: Long): List<WorkoutRecordEntity> = TODO()
        override suspend fun getAverageTrimp(fromMs: Long, toMs: Long): Float? = TODO()
        override suspend fun getTotalTrimp(fromMs: Long, toMs: Long): Float? = TODO()
        override suspend fun getTrimpPoints(fromMs: Long, toMs: Long): List<TimestampedTrimp> = TODO()
        override suspend fun getWorkoutsInRange(fromMs: Long, toMs: Long): List<WorkoutRecordEntity> = TODO()
        override suspend fun getOverlapping(fromMs: Long, toMs: Long): List<WorkoutRecordEntity> = TODO()
        override suspend fun getEarliestWorkoutTimestamp(): Long? = TODO()
        override suspend fun getTotalDurationMinutes(fromMs: Long, toMs: Long): Int? = TODO()
        override suspend fun getWeightedAvgHr(fromMs: Long, toMs: Long): Float? = TODO()
        override suspend fun deleteBeforeTimestamp(beforeMs: Long): Int = TODO()
        override suspend fun deleteById(id: String): Int = TODO()
        override suspend fun count(): Int = TODO()
        override suspend fun countInRange(startMs: Long, endMs: Long): Int = TODO()
        override suspend fun deleteAll(): Int = TODO()
        override suspend fun getDistinctDeviceNames(): List<String> = TODO()
        override suspend fun deleteRecordsNotMatchingDevice(fromMs: Long, toMs: Long, deviceName: String): Int = TODO()
    }

    private class FakeWorkoutRoutePointDao : WorkoutRoutePointDao {
        val points = mutableListOf<WorkoutRoutePointEntity>()

        override suspend fun insertAll(points: List<WorkoutRoutePointEntity>) {
            this.points.addAll(points)
        }

        override suspend fun getRoutePoints(workoutId: String): List<WorkoutRoutePointEntity> {
            return points.filter { it.workoutId == workoutId }
        }

        override suspend fun deleteByWorkoutId(workoutId: String): Int {
            val before = points.size
            points.removeAll { it.workoutId == workoutId }
            return before - points.size
        }
    }
}
