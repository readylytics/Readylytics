package app.readylytics.health.domain.sync

import app.readylytics.health.domain.model.DomainExerciseSessionRecord
import app.readylytics.health.domain.model.DomainRouteLocation
import app.readylytics.health.domain.model.RouteState
import app.readylytics.health.domain.repository.HealthConnectRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SyncWorkoutRouteUseCaseTest {
    private val hcRepo = mockk<HealthConnectRepository>()
    private val healthIngestionStore = mockk<HealthIngestionStore>(relaxed = true)

    private val useCase =
        SyncWorkoutRouteUseCase(
            hcRepo = hcRepo,
            healthIngestionStore = healthIngestionStore,
        )

    @Test
    fun `invoke fails when exercise session is not found`() =
        runTest {
            coEvery { hcRepo.readExerciseSession("missing-workout") } returns null

            val result = useCase.invoke("missing-workout")

            assertTrue(result.isFailure)
            coVerify(exactly = 0) { healthIngestionStore.persistSingleWorkoutRoute(any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `invoke persists route points and updates routeState when session has route`() =
        runTest {
            val start = Instant.parse("2026-08-15T10:00:00Z")
            val end = Instant.parse("2026-08-15T10:30:00Z")
            val locations =
                listOf(
                    DomainRouteLocation(
                        time = start,
                        latitude = 37.7749,
                        longitude = -122.4194,
                        altitudeMeters = 10.0,
                        horizontalAccuracyMeters = 5f,
                        verticalAccuracyMeters = 3f,
                    ),
                    DomainRouteLocation(
                        time = end,
                        latitude = 37.7750,
                        longitude = -122.4195,
                        altitudeMeters = 15.0,
                        horizontalAccuracyMeters = 5f,
                        verticalAccuracyMeters = 3f,
                    ),
                )
            val session =
                DomainExerciseSessionRecord(
                    id = "workout-123",
                    startTime = start,
                    endTime = end,
                    exerciseType = "56", // Running
                    deviceName = "Pixel Watch",
                    routePoints = locations,
                    routeState = RouteState.IMPORTED,
                )

            coEvery { hcRepo.readExerciseSession("workout-123") } returns session

            val result = useCase.invoke("workout-123")

            assertTrue(result.isSuccess)
            val routeStateSlot = slot<String>()
            coVerify(exactly = 1) {
                healthIngestionStore.persistSingleWorkoutRoute(
                    workoutId = "workout-123",
                    routePoints = match { it.size == 2 },
                    routeState = capture(routeStateSlot),
                    totalDistanceMeters = any(),
                    avgSpeedKmh = any(),
                    elevationGainMeters = any(),
                )
            }
            assertEquals(RouteState.IMPORTED, routeStateSlot.captured)
        }

    @Test
    fun `invoke persists consent required when route result requires consent`() =
        runTest {
            val start = Instant.parse("2026-08-15T10:00:00Z")
            val end = Instant.parse("2026-08-15T10:30:00Z")
            val session =
                DomainExerciseSessionRecord(
                    id = "workout-456",
                    startTime = start,
                    endTime = end,
                    exerciseType = "56",
                    deviceName = "Pixel Watch",
                    routePoints = emptyList(),
                    routeState = RouteState.PERMISSION_REQUIRED,
                )

            coEvery { hcRepo.readExerciseSession("workout-456") } returns session

            val result = useCase.invoke("workout-456")

            assertTrue(result.isSuccess)
            val routeStateSlot = slot<String>()
            coVerify(exactly = 1) {
                healthIngestionStore.persistSingleWorkoutRoute(
                    workoutId = "workout-456",
                    routePoints = match { it.isEmpty() },
                    routeState = capture(routeStateSlot),
                    totalDistanceMeters = any(),
                    avgSpeedKmh = any(),
                    elevationGainMeters = any(),
                )
            }
            assertEquals(RouteState.PERMISSION_REQUIRED, routeStateSlot.captured)
        }

    @Test
    fun `invoke persists granted route points over a consent-required session`() =
        runTest {
            val start = Instant.parse("2026-08-15T10:00:00Z")
            val end = Instant.parse("2026-08-15T10:30:00Z")
            val session =
                DomainExerciseSessionRecord(
                    id = "workout-789",
                    startTime = start,
                    endTime = end,
                    exerciseType = "56",
                    deviceName = "Pixel Watch",
                    routePoints = emptyList(),
                    routeState = RouteState.PERMISSION_REQUIRED,
                )
            val granted =
                listOf(
                    DomainRouteLocation(
                        time = start,
                        latitude = 37.7749,
                        longitude = -122.4194,
                        altitudeMeters = 10.0,
                        horizontalAccuracyMeters = 5f,
                        verticalAccuracyMeters = 3f,
                    ),
                    DomainRouteLocation(
                        time = end,
                        latitude = 37.7850,
                        longitude = -122.4194,
                        altitudeMeters = 25.0,
                        horizontalAccuracyMeters = 5f,
                        verticalAccuracyMeters = 3f,
                    ),
                )

            coEvery { hcRepo.readExerciseSession("workout-789") } returns session

            val result = useCase.invoke("workout-789", grantedRoutePoints = granted)

            assertTrue(result.isSuccess)
            val routeStateSlot = slot<String>()
            val distanceSlot = slot<Float?>()
            coVerify(exactly = 1) {
                healthIngestionStore.persistSingleWorkoutRoute(
                    workoutId = "workout-789",
                    routePoints = match { it.size == 2 },
                    routeState = capture(routeStateSlot),
                    totalDistanceMeters = captureNullable(distanceSlot),
                    avgSpeedKmh = any(),
                    elevationGainMeters = any(),
                )
            }
            assertEquals(RouteState.IMPORTED, routeStateSlot.captured)
            // Derived from the granted polyline, since the session itself carried no distance.
            assertTrue((distanceSlot.captured ?: 0f) > 0f)
        }

    @Test
    fun `invoke ignores empty granted route points and keeps the session route state`() =
        runTest {
            val start = Instant.parse("2026-08-15T10:00:00Z")
            val end = Instant.parse("2026-08-15T10:30:00Z")
            val session =
                DomainExerciseSessionRecord(
                    id = "workout-000",
                    startTime = start,
                    endTime = end,
                    exerciseType = "56",
                    deviceName = "Pixel Watch",
                    routePoints = emptyList(),
                    routeState = RouteState.PERMISSION_REQUIRED,
                )

            coEvery { hcRepo.readExerciseSession("workout-000") } returns session

            val result = useCase.invoke("workout-000", grantedRoutePoints = emptyList())

            assertTrue(result.isSuccess)
            val routeStateSlot = slot<String>()
            coVerify(exactly = 1) {
                healthIngestionStore.persistSingleWorkoutRoute(
                    workoutId = "workout-000",
                    routePoints = match { it.isEmpty() },
                    routeState = capture(routeStateSlot),
                    totalDistanceMeters = any(),
                    avgSpeedKmh = any(),
                    elevationGainMeters = any(),
                )
            }
            assertEquals(RouteState.PERMISSION_REQUIRED, routeStateSlot.captured)
        }
}
