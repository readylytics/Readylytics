package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.databaseschema.data.local.dao.WorkoutDao
import app.readylytics.health.core.databaseschema.data.local.dao.WorkoutRoutePointDao
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRoutePointEntity
import app.readylytics.health.domain.model.WorkoutRoutePoint
import app.readylytics.health.core.model.domain.repository.WorkoutData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.Proxy

class WorkoutRepositoryImplTest {
    private val results = mutableMapOf<String, Any?>()
    private val dao = fakeDao<WorkoutDao>(results)
    private val routePointDao = fakeDao<WorkoutRoutePointDao>(results)
    private val repository = WorkoutRepositoryImpl(dao, routePointDao)

    @Test
    fun `getInRange delegates to DAO and maps entities to domain WorkoutData`() =
        runTest {
            val entity =
                WorkoutRecordEntity(
                    id = "w1",
                    startTime = 100L,
                    endTime = 200L,
                    exerciseType = "Running",
                    durationMinutes = 45,
                    zone1Minutes = 10f,
                    zone2Minutes = 15f,
                    zone3Minutes = 10f,
                    zone4Minutes = 5f,
                    zone5Minutes = 0f,
                    trimp = 120f,
                    avgHr = 150f,
                    deviceName = "Watch",
                    modelTrimp = 130f,
                )
            results["getWorkoutsInRange"] = listOf(entity)

            val result = repository.getInRange(100L, 200L)

            assertEquals(1, result.size)
            val mapped = result.first()
            assertEquals("w1", mapped.id)
            assertEquals(100L, mapped.startTime)
            assertEquals(200L, mapped.endTime)
            assertEquals("Running", mapped.exerciseType)
            assertEquals(45, mapped.durationMinutes)
            assertEquals(10f, mapped.zone1Minutes)
            assertEquals(15f, mapped.zone2Minutes)
            assertEquals(10f, mapped.zone3Minutes)
            assertEquals(5f, mapped.zone4Minutes)
            assertEquals(0f, mapped.zone5Minutes)
            assertEquals(120f, mapped.trimp)
            assertEquals(150f, mapped.avgHr)
            assertEquals("Watch", mapped.deviceName)
        }

    @Test
    fun `getInRange returns empty list when DAO returns empty`() =
        runTest {
            results["getWorkoutsInRange"] = emptyList<WorkoutRecordEntity>()

            val result = repository.getInRange(100L, 200L)

            assertEquals(emptyList<WorkoutData>(), result)
        }

    @Test
    fun `getInRange preserves null device name`() =
        runTest {
            val entity =
                WorkoutRecordEntity(
                    id = "w2",
                    startTime = 1L,
                    endTime = 2L,
                    exerciseType = "Cycling",
                    durationMinutes = 30,
                    zone1Minutes = 5f,
                    zone2Minutes = 5f,
                    zone3Minutes = 5f,
                    zone4Minutes = 5f,
                    zone5Minutes = 5f,
                    trimp = 60f,
                    avgHr = 130f,
                )
            results["getWorkoutsInRange"] = listOf(entity)

            val result = repository.getInRange(1L, 2L)

            assertNull(result.single().deviceName)
        }

    @Test
    fun `getById still delegates correctly`() =
        runTest {
            val entity =
                WorkoutRecordEntity(
                    id = "w3",
                    startTime = 10L,
                    endTime = 20L,
                    exerciseType = "Swimming",
                    durationMinutes = 20,
                    zone1Minutes = 0f,
                    zone2Minutes = 0f,
                    zone3Minutes = 0f,
                    zone4Minutes = 0f,
                    zone5Minutes = 0f,
                    trimp = 30f,
                    avgHr = 120f,
                )
            results["getById"] = entity

            assertEquals("w3", repository.getById("w3")?.id)
        }

    @Test
    fun `observeSince maps entities to domain`() =
        runTest {
            val entity =
                WorkoutRecordEntity(
                    id = "w4",
                    startTime = 10L,
                    endTime = 20L,
                    exerciseType = "Strength",
                    durationMinutes = 40,
                    zone1Minutes = 0f,
                    zone2Minutes = 0f,
                    zone3Minutes = 0f,
                    zone4Minutes = 0f,
                    zone5Minutes = 0f,
                    trimp = 50f,
                    avgHr = 110f,
                )
            results["observeSince"] = flowOf(listOf(entity))

            assertEquals(1, repository.observeSince(0L).first().size)
        }

    @Test
    fun `getInRangePaged delegates to DAO and maps entities to domain`() =
        runTest {
            val entity =
                WorkoutRecordEntity(
                    id = "w5",
                    startTime = 300L,
                    endTime = 400L,
                    exerciseType = "Running",
                    durationMinutes = 60,
                    zone1Minutes = 10f,
                    zone2Minutes = 20f,
                    zone3Minutes = 15f,
                    zone4Minutes = 10f,
                    zone5Minutes = 5f,
                    trimp = 200f,
                    avgHr = 160f,
                    deviceName = "Watch",
                    totalDistanceMeters = 5000f,
                    avgSpeedKmh = 12.5f,
                    elevationGainMeters = 45f,
                    routeState = "IMPORTED",
                )
            results["getPagedInRange"] = listOf(entity)

            val result = repository.getInRangePaged(100L, 500L, 10, 0)

            assertEquals(1, result.size)
            val mapped = result.first()
            assertEquals("w5", mapped.id)
            assertEquals(300L, mapped.startTime)
            assertEquals(400L, mapped.endTime)
            assertEquals("Running", mapped.exerciseType)
            assertEquals(60, mapped.durationMinutes)
            assertEquals(200f, mapped.trimp)
            assertEquals(160f, mapped.avgHr)
            assertEquals("Watch", mapped.deviceName)
            assertEquals(5000f, mapped.totalDistanceMeters)
            assertEquals(12.5f, mapped.avgSpeedKmh)
            assertEquals(45f, mapped.elevationGainMeters)
            assertEquals("IMPORTED", mapped.routeState)
        }

    @Test
    fun `countByTimeRange delegates to DAO`() =
        runTest {
            results["countByTimeRange"] = 7

            assertEquals(7, repository.countByTimeRange(100L, 500L))
        }

    @Test
    fun `getRoutePoints delegates to the route point dao and maps to domain`() =
        runTest {
            val entities =
                listOf(
                    WorkoutRoutePointEntity(
                        id = 1L,
                        workoutId = "w1",
                        latitude = 1.0,
                        longitude = 2.0,
                        altitude = 100.0,
                        timestampMs = 100L,
                        horizontalAccuracy = 5f,
                        verticalAccuracy = 10f,
                    ),
                )
            results["getRoutePoints"] = entities

            val expected =
                listOf(
                    WorkoutRoutePoint(
                        id = 1L,
                        workoutId = "w1",
                        latitude = 1.0,
                        longitude = 2.0,
                        altitude = 100.0,
                        timestampMs = 100L,
                        horizontalAccuracy = 5f,
                        verticalAccuracy = 10f,
                    ),
                )
            assertEquals(expected, repository.getRoutePoints("w1"))
        }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> fakeDao(results: MutableMap<String, Any?>): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            results[method.name]
                ?: when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    else -> null
                }
        } as T
}
