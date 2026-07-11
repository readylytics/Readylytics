package app.readylytics.health.data.healthconnect

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.units.Length
import app.readylytics.health.domain.model.DomainExerciseRoute
import app.readylytics.health.domain.model.DomainRoutePoint
import app.readylytics.health.domain.repository.PermissionStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class HealthConnectRepositoryImplTest {
    private val context = mockk<Context>(relaxed = true)
    private val ioDispatcher = StandardTestDispatcher()
    private val mockClient = mockk<HealthConnectClient>()
    
    private lateinit var repository: HealthConnectRepositoryImpl

    @Before
    fun setUp() {
        mockkObject(HealthConnectClient.Companion)
        every { HealthConnectClient.getOrCreate(any()) } returns mockClient
        mockkStatic("app.readylytics.health.data.healthconnect.HealthConnectRepositoryImplKt")
        mockkStatic(Log::class)
        every { Log.e(any(), any()) } returns 0
        
        repository = HealthConnectRepositoryImpl(
            context = context,
            ioDispatcher = ioDispatcher,
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun readExerciseRoute_whenRouteExists_returnsMappedRoute() = runTest(ioDispatcher) {
        val sessionId = "session-123"
        val timePoint = Instant.parse("2026-05-01T12:00:00Z")
        val mockLocation = mockk<ExerciseRoute.Location> {
            every { latitude } returns 37.7749
            every { longitude } returns -122.4194
            every { altitude } returns Length.meters(10.0)
            every { time } returns timePoint
            every { horizontalAccuracy } returns Length.meters(2.0)
            every { verticalAccuracy } returns Length.meters(3.0)
        }
        
        val mockRoute = mockk<ExerciseRoute> {
            every { route } returns listOf(mockLocation)
        }
        
        val mockRouteResult = ExerciseRouteResult.Data(mockRoute)
        coEvery { mockClient.getExerciseRoute(sessionId) } returns mockRouteResult

        val result = repository.readExerciseRoute(sessionId)

        coVerify(exactly = 1) { mockClient.getExerciseRoute(sessionId) }
        
        val expected = DomainExerciseRoute(
            workoutId = sessionId,
            points = listOf(
                DomainRoutePoint(
                    latitude = 37.7749,
                    longitude = -122.4194,
                    altitude = 10.0,
                    timestampMs = timePoint.toEpochMilli(),
                    horizontalAccuracy = 2.0f,
                    verticalAccuracy = 3.0f
                )
            )
        )
        assertEquals(expected, result)
    }

    @Test
    fun readExerciseRoute_whenNoRouteData_returnsNull() = runTest(ioDispatcher) {
        val sessionId = "session-123"
        coEvery { mockClient.getExerciseRoute(sessionId) } returns ExerciseRouteResult.NoData()

        val result = repository.readExerciseRoute(sessionId)

        assertNull(result)
    }

    @Test
    fun readExerciseRoute_whenConsentRequired_returnsNull() = runTest(ioDispatcher) {
        val sessionId = "session-123"
        coEvery { mockClient.getExerciseRoute(sessionId) } returns ExerciseRouteResult.ConsentRequired()

        val result = repository.readExerciseRoute(sessionId)

        assertNull(result)
    }

    @Test
    fun readExerciseRoute_whenSecurityExceptionThrown_returnsNull() = runTest(ioDispatcher) {
        val sessionId = "session-123"
        coEvery { mockClient.getExerciseRoute(sessionId) } throws SecurityException("No permission")

        val result = repository.readExerciseRoute(sessionId)

        assertNull(result)
    }

    @Test
    fun readExerciseRoute_whenGeneralExceptionThrown_returnsNull() = runTest(ioDispatcher) {
        val sessionId = "session-123"
        coEvery { mockClient.getExerciseRoute(sessionId) } throws RuntimeException("Boom")

        val result = repository.readExerciseRoute(sessionId)

        assertNull(result)
    }

    @Test
    fun readExerciseRoute_whenCancelled_rethrowsCancellation() = runTest(ioDispatcher) {
        coEvery { mockClient.getExerciseRoute("session-123") } throws CancellationException("cancelled")

        assertFailsWith<CancellationException> {
            repository.readExerciseRoute("session-123")
        }
    }

    @Test
    fun checkExerciseRoutePermission_whenOptionalPermissionIsMissing_reportsIt() = runTest(ioDispatcher) {
        every { HealthConnectClient.getSdkStatus(context) } returns HealthConnectClient.SDK_AVAILABLE
        coEvery { mockClient.permissionController.getGrantedPermissions() } returns emptySet()

        assertEquals(
            PermissionStatus.Missing(setOf("android.permission.health.READ_EXERCISE_ROUTES")),
            repository.checkExerciseRoutePermission(),
        )
    }
}
