package app.readylytics.health.core.healthconnect.data.healthconnect

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.ReadRecordResponse
import androidx.health.connect.client.response.ReadRecordsResponse
import androidx.health.connect.client.units.Length
import app.readylytics.health.core.model.domain.model.WorkoutRoutePoint
import app.readylytics.health.core.model.domain.repository.ReadOutcome
import app.readylytics.health.core.model.domain.sync.WorkoutInput
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.Instant

class WorkoutReadPreparerTest {
    private val client = mockk<HealthConnectClient>()
    private lateinit var preparer: WorkoutReadPreparer

    private val t0 = Instant.parse("2026-06-01T10:00:00Z")
    private val t1 = Instant.parse("2026-06-01T11:00:00Z")
    private val sessionId = "session-test-123"
    private val packageName = "com.strava"

    @Before
    fun setUp() {
        preparer = WorkoutReadPreparer(client)

        // Default: distance and elevation return empty pages so route tests don't fail on them
        coEvery {
            client.readRecords<DistanceRecord>(match { it.recordType == DistanceRecord::class })
        } returns emptyPage()
        coEvery {
            client.readRecords<ElevationGainedRecord>(match { it.recordType == ElevationGainedRecord::class })
        } returns emptyPage()
    }

    @Test
    fun `prepare with existing ExerciseRouteResult Data maps points without calling client readRecord`() =
        runTest {
            val location1 = ExerciseRoute.Location(
                time = t0.plusSeconds(60),
                latitude = 52.5200,
                longitude = 13.4050,
                horizontalAccuracy = Length.meters(4.0),
                verticalAccuracy = Length.meters(6.0),
                altitude = Length.meters(35.0),
            )
            val location2 = ExerciseRoute.Location(
                time = t0.plusSeconds(120),
                latitude = 52.5210,
                longitude = 13.4060,
            )
            val sessionWithData = mockExerciseSession(
                routeResult = ExerciseRouteResult.Data(ExerciseRoute(listOf(location2, location1))),
            )

            val prepared = preparer.prepare(sessionWithData, baseWorkout())

            coVerify(exactly = 0) { client.readRecord(ExerciseSessionRecord::class, any()) }
            assertTrue(prepared.route is ReadOutcome.Available)
            val points = (prepared.route as ReadOutcome.Available).data
            assertEquals(2, points.size)
            // Sorted by timestampMs
            assertEquals(t0.plusSeconds(60).toEpochMilli(), points[0].timestampMs)
            assertEquals(52.5200, points[0].latitude, 0.0001)
            assertEquals(35.0, points[0].altitude!!, 0.001)
            assertEquals(4.0f, points[0].horizontalAccuracy!!, 0.001f)
            assertEquals(6.0f, points[0].verticalAccuracy!!, 0.001f)
            assertEquals(t0.plusSeconds(120).toEpochMilli(), points[1].timestampMs)
            assertNull(points[1].altitude)
        }

    @Test
    fun `prepare with NoData from changes API reads true record and returns Available points`() =
        runTest {
            val sessionFromChanges = mockExerciseSession(routeResult = ExerciseRouteResult.NoData())
            val location = ExerciseRoute.Location(
                time = t0.plusSeconds(30),
                latitude = 48.8566,
                longitude = 2.3522,
            )
            val fetchedSession = mockExerciseSession(
                routeResult = ExerciseRouteResult.Data(ExerciseRoute(listOf(location))),
            )
            coEvery { client.readRecord(ExerciseSessionRecord::class, sessionId) } returns mockk {
                every { record } returns fetchedSession
            }

            val prepared = preparer.prepare(sessionFromChanges, baseWorkout())

            coVerify(exactly = 1) { client.readRecord(ExerciseSessionRecord::class, sessionId) }
            assertTrue(prepared.route is ReadOutcome.Available)
            val points = (prepared.route as ReadOutcome.Available).data
            assertEquals(1, points.size)
            assertEquals(sessionId, points[0].workoutId)
            assertEquals(48.8566, points[0].latitude, 0.0001)
        }

    @Test
    fun `prepare with NoData where client readRecord returns ConsentRequired returns Denied`() =
        runTest {
            val sessionFromChanges = mockExerciseSession(routeResult = ExerciseRouteResult.NoData())
            val fetchedSession = mockExerciseSession(routeResult = ExerciseRouteResult.ConsentRequired())
            coEvery { client.readRecord(ExerciseSessionRecord::class, sessionId) } returns mockk {
                every { record } returns fetchedSession
            }

            val prepared = preparer.prepare(sessionFromChanges, baseWorkout())

            assertEquals(ReadOutcome.Denied, prepared.route)
        }

    @Test
    fun `prepare with NoData where client readRecord returns NoData returns Available emptyList`() =
        runTest {
            val sessionFromChanges = mockExerciseSession(routeResult = ExerciseRouteResult.NoData())
            val fetchedSession = mockExerciseSession(routeResult = ExerciseRouteResult.NoData())
            coEvery { client.readRecord(ExerciseSessionRecord::class, sessionId) } returns mockk {
                every { record } returns fetchedSession
            }

            val prepared = preparer.prepare(sessionFromChanges, baseWorkout())

            assertTrue(prepared.route is ReadOutcome.Available)
            val points = (prepared.route as ReadOutcome.Available).data
            assertTrue(points.isEmpty())
        }

    @Test
    fun `prepare when client readRecord throws SecurityException returns Denied`() =
        runTest {
            val sessionFromChanges = mockExerciseSession(routeResult = ExerciseRouteResult.NoData())
            coEvery { client.readRecord(ExerciseSessionRecord::class, sessionId) } throws
                SecurityException("Route permission not granted")

            val prepared = preparer.prepare(sessionFromChanges, baseWorkout())

            assertEquals(ReadOutcome.Denied, prepared.route)
        }

    @Test
    fun `prepare when client readRecord throws CancellationException rethrows`() =
        runTest {
            val sessionFromChanges = mockExerciseSession(routeResult = ExerciseRouteResult.NoData())
            coEvery { client.readRecord(ExerciseSessionRecord::class, sessionId) } throws
                CancellationException("Cancelled")

            try {
                preparer.prepare(sessionFromChanges, baseWorkout())
                fail("Expected CancellationException to be thrown")
            } catch (e: CancellationException) {
                assertEquals("Cancelled", e.message)
            }
        }

    @Test
    fun `prepare when client readRecord throws transient error propagates for retry`() =
        runTest {
            val sessionFromChanges = mockExerciseSession(routeResult = ExerciseRouteResult.NoData())
            coEvery { client.readRecord(ExerciseSessionRecord::class, sessionId) } throws
                IOException("Health Connect binder died")

            try {
                preparer.prepare(sessionFromChanges, baseWorkout())
                fail("Expected IOException to be thrown")
            } catch (e: IOException) {
                assertEquals("Health Connect binder died", e.message)
            }
        }

    @Test
    fun `prepare distance read paginates all pages and resolves total distance`() =
        runTest {
            val session = mockExerciseSession(routeResult = ExerciseRouteResult.NoData())
            coEvery { client.readRecord(ExerciseSessionRecord::class, sessionId) } returns mockk {
                every { record } returns session
            }

            val d1 = mockDistanceRecord(1200.0, t0, t0.plusSeconds(1800), packageName)
            val d2 = mockDistanceRecord(1800.0, t0.plusSeconds(1800), t1, packageName)

            val page1 = mockk<ReadRecordsResponse<DistanceRecord>> {
                every { records } returns listOf(d1)
                every { pageToken } returns "page-2"
            }
            val page2 = mockk<ReadRecordsResponse<DistanceRecord>> {
                every { records } returns listOf(d2)
                every { pageToken } returns null
            }

            coEvery {
                client.readRecords<DistanceRecord>(
                    match { it.recordType == DistanceRecord::class && it.pageToken == null },
                )
            } returns page1
            coEvery {
                client.readRecords<DistanceRecord>(
                    match { it.recordType == DistanceRecord::class && it.pageToken == "page-2" },
                )
            } returns page2

            val prepared = preparer.prepare(session, baseWorkout())

            assertTrue(prepared.distanceMeters is ReadOutcome.Available)
            assertEquals(3000.0f, (prepared.distanceMeters as ReadOutcome.Available).data!!, 0.01f)
        }

    @Test
    fun `prepare distance read returns Available null when no matching intervals`() =
        runTest {
            val session = mockExerciseSession(routeResult = ExerciseRouteResult.NoData())
            coEvery { client.readRecord(ExerciseSessionRecord::class, sessionId) } returns mockk {
                every { record } returns session
            }
            // Different package, won't match
            val d1 = mockDistanceRecord(500.0, t0, t1, "com.other.app")
            coEvery {
                client.readRecords<DistanceRecord>(match { it.recordType == DistanceRecord::class })
            } returns mockk {
                every { records } returns listOf(d1)
                every { pageToken } returns null
            }

            val prepared = preparer.prepare(session, baseWorkout())

            assertTrue(prepared.distanceMeters is ReadOutcome.Available)
            assertNull((prepared.distanceMeters as ReadOutcome.Available).data)
        }

    @Test
    fun `prepare distance read returns Denied on SecurityException`() =
        runTest {
            val session = mockExerciseSession(routeResult = ExerciseRouteResult.NoData())
            coEvery { client.readRecord(ExerciseSessionRecord::class, sessionId) } returns mockk {
                every { record } returns session
            }
            coEvery { client.readRecords<DistanceRecord>(match { it.recordType == DistanceRecord::class }) } throws
                SecurityException("Distance read denied")

            val prepared = preparer.prepare(session, baseWorkout())

            assertEquals(ReadOutcome.Denied, prepared.distanceMeters)
        }

    @Test
    fun `prepare elevation read paginates all pages and resolves total elevation`() =
        runTest {
            val session = mockExerciseSession(routeResult = ExerciseRouteResult.NoData())
            coEvery { client.readRecord(ExerciseSessionRecord::class, sessionId) } returns mockk {
                every { record } returns session
            }

            val e1 = mockElevationRecord(25.0, t0, t0.plusSeconds(1800), packageName)
            val e2 = mockElevationRecord(15.0, t0.plusSeconds(1800), t1, packageName)

            val page1 = mockk<ReadRecordsResponse<ElevationGainedRecord>> {
                every { records } returns listOf(e1)
                every { pageToken } returns "elev-page-2"
            }
            val page2 = mockk<ReadRecordsResponse<ElevationGainedRecord>> {
                every { records } returns listOf(e2)
                every { pageToken } returns null
            }

            coEvery {
                client.readRecords<ElevationGainedRecord>(
                    match { it.recordType == ElevationGainedRecord::class && it.pageToken == null },
                )
            } returns page1
            coEvery {
                client.readRecords<ElevationGainedRecord>(
                    match { it.recordType == ElevationGainedRecord::class && it.pageToken == "elev-page-2" },
                )
            } returns page2

            val prepared = preparer.prepare(session, baseWorkout())

            assertTrue(prepared.elevationMeters is ReadOutcome.Available)
            assertEquals(40.0f, (prepared.elevationMeters as ReadOutcome.Available).data!!, 0.01f)
        }

    @Test
    fun `prepare elevation read returns Denied on SecurityException`() =
        runTest {
            val session = mockExerciseSession(routeResult = ExerciseRouteResult.NoData())
            coEvery { client.readRecord(ExerciseSessionRecord::class, sessionId) } returns mockk {
                every { record } returns session
            }
            coEvery {
                client.readRecords<ElevationGainedRecord>(match { it.recordType == ElevationGainedRecord::class })
            } throws SecurityException("Elevation read denied")

            val prepared = preparer.prepare(session, baseWorkout())

            assertEquals(ReadOutcome.Denied, prepared.elevationMeters)
        }

    @Test
    fun `provider reads assert false when transaction is simulated active`() =
        runTest {
            var transactionActive = false
            val sessionFromChanges = mockExerciseSession(routeResult = ExerciseRouteResult.NoData())
            val fetchedSession = mockExerciseSession(routeResult = ExerciseRouteResult.NoData())

            coEvery { client.readRecord(ExerciseSessionRecord::class, sessionId) } answers {
                assertFalse("readRecord must never run inside a transaction", transactionActive)
                mockk { every { record } returns fetchedSession }
            }
            coEvery {
                client.readRecords<DistanceRecord>(match { it.recordType == DistanceRecord::class })
            } answers {
                assertFalse("readRecords must never run inside a transaction", transactionActive)
                emptyPage()
            }
            coEvery {
                client.readRecords<ElevationGainedRecord>(match { it.recordType == ElevationGainedRecord::class })
            } answers {
                assertFalse("readRecords must never run inside a transaction", transactionActive)
                emptyPage()
            }

            // Outside transaction -> succeeds
            preparer.prepare(sessionFromChanges, baseWorkout())

            // Inside transaction -> would fail assertion
            transactionActive = true
            try {
                preparer.prepare(sessionFromChanges, baseWorkout())
                fail("Expected assertion error when transactionActive is true")
            } catch (e: AssertionError) {
                assertTrue(e.message?.contains("must never run inside a transaction") == true)
            }
        }

    private fun baseWorkout(): WorkoutInput =
        WorkoutInput(
            id = sessionId,
            startTime = t0.toEpochMilli(),
            endTime = t1.toEpochMilli(),
            exerciseType = "56",
            durationMinutes = 60,
            zone1Minutes = 0f,
            zone2Minutes = 0f,
            zone3Minutes = 0f,
            zone4Minutes = 0f,
            zone5Minutes = 0f,
            trimp = 100f,
            avgHr = 150f,
            deviceName = "Watch",
        )

    private fun mockExerciseSession(
        routeResult: ExerciseRouteResult,
    ): ExerciseSessionRecord {
        val origin = mockk<DataOrigin>(relaxed = true) {
            every { packageName } returns this@WorkoutReadPreparerTest.packageName
        }
        val meta = mockk<Metadata>(relaxed = true) {
            every { id } returns sessionId
            every { dataOrigin } returns origin
        }
        return mockk(relaxed = true) {
            every { metadata } returns meta
            every { startTime } returns t0
            every { endTime } returns t1
            every { exerciseRouteResult } returns routeResult
        }
    }

    private fun mockDistanceRecord(
        meters: Double,
        start: Instant,
        end: Instant,
        pkg: String,
    ): DistanceRecord =
        mockk(relaxed = true) {
            every { metadata.dataOrigin.packageName } returns pkg
            every { distance } returns Length.meters(meters)
            every { startTime } returns start
            every { endTime } returns end
        }

    private fun mockElevationRecord(
        meters: Double,
        start: Instant,
        end: Instant,
        pkg: String,
    ): ElevationGainedRecord =
        mockk(relaxed = true) {
            every { metadata.dataOrigin.packageName } returns pkg
            every { elevation } returns Length.meters(meters)
            every { startTime } returns start
            every { endTime } returns end
        }

    private fun <T : Record> emptyPage(): ReadRecordsResponse<T> =
        mockk {
            every { records } returns emptyList()
            every { pageToken } returns null
        }
}
