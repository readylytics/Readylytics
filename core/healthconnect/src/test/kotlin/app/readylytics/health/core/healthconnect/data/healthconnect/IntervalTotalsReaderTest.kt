package app.readylytics.health.core.healthconnect.data.healthconnect

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.units.Length
import app.readylytics.health.core.model.domain.model.DomainIntervalTotal
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.After
import org.junit.Test
import java.time.Instant

class IntervalTotalsReaderTest {
    private val client = mockk<HealthConnectClient>(relaxed = true)
    private val ioDispatcher = Dispatchers.Unconfined
    private lateinit var reader: IntervalTotalsReader

    private val t0 = Instant.parse("2026-05-01T00:00:00Z")
    private val t1 = Instant.parse("2026-05-01T01:00:00Z")

    @Before
    fun setup() {
        reader =
            IntervalTotalsReader(
                context = mockk(relaxed = true),
                ioDispatcher = ioDispatcher,
                client = client,
            )
        mockkObject(HealthConnectClient)
        every { HealthConnectClient.getSdkStatus(any()) } returns HealthConnectClient.SDK_AVAILABLE
    }

    @After
    fun tearDown() {
        unmockkObject(HealthConnectClient)
    }

    private fun mockDistanceRecord(
        meters: Double,
        start: Instant,
        end: Instant,
        packageName: String,
    ): DistanceRecord =
        mockk(relaxed = true) {
            every { metadata.id } returns "dist-1"
            every { metadata.dataOrigin.packageName } returns packageName
            every { distance } returns Length.meters(meters)
            every { startTime } returns start
            every { endTime } returns end
        }

    private fun mockElevationRecord(
        meters: Double,
        start: Instant,
        end: Instant,
        packageName: String,
    ): ElevationGainedRecord =
        mockk(relaxed = true) {
            every { metadata.id } returns "elev-1"
            every { metadata.dataOrigin.packageName } returns packageName
            every { elevation } returns Length.meters(meters)
            every { startTime } returns start
            every { endTime } returns end
        }

    private fun mockExerciseSession(
        start: Instant,
        end: Instant,
        packageName: String,
    ): ExerciseSessionRecord =
        mockk(relaxed = true) {
            every { metadata.id } returns "session-1"
            every { metadata.dataOrigin.packageName } returns packageName
            every { startTime } returns start
            every { endTime } returns end
        }

    @Test
    fun `readDistanceTotals returns mapped totals on success`() =
        runTest {
            val distRecord = mockDistanceRecord(5000.0, t0, t1, "com.strava")
            coEvery {
                client.readRecords<DistanceRecord>(any())
            } returns mockk {
                every { records } returns listOf(distRecord)
                every { pageToken } returns null
            }

            val outcome = reader.readDistanceTotals(t0, t1)
            assertTrue(outcome is app.readylytics.health.core.model.domain.repository.ReadOutcome.Available)
            val totals = (outcome as app.readylytics.health.core.model.domain.repository.ReadOutcome.Available).data
            assertEquals(1, totals.size)
            assertEquals(5000.0, totals.first().value, 0.01)
            assertEquals("com.strava", totals.first().originPackage)
        }

    @Test
    fun `readElevationTotals returns mapped totals on success`() =
        runTest {
            val elevRecord = mockElevationRecord(150.0, t0, t1, "com.strava")
            coEvery {
                client.readRecords<ElevationGainedRecord>(any())
            } returns mockk {
                every { records } returns listOf(elevRecord)
                every { pageToken } returns null
            }

            val outcome = reader.readElevationTotals(t0, t1)
            assertTrue(outcome is app.readylytics.health.core.model.domain.repository.ReadOutcome.Available)
            val totals = (outcome as app.readylytics.health.core.model.domain.repository.ReadOutcome.Available).data
            assertEquals(1, totals.size)
            assertEquals(150.0, totals.first().value, 0.01)
        }

    @Test
    fun `readIntervalTotals returns empty list on SecurityException`() =
        runTest {
            coEvery {
                client.readRecords<DistanceRecord>(any())
            } throws SecurityException("Permission denied")

            val outcome = reader.readDistanceTotals(t0, t1)
            assertEquals(app.readylytics.health.core.model.domain.repository.ReadOutcome.Denied, outcome)
        }

    @Test
    fun `readDistanceTotals returns Unsupported without invoking client when provider is unavailable`() =
        runTest {
            every { HealthConnectClient.getSdkStatus(any()) } returns HealthConnectClient.SDK_UNAVAILABLE

            val result = reader.readDistanceTotals(t0, t1)

            assertEquals(app.readylytics.health.core.model.domain.repository.ReadOutcome.Unsupported, result)
            coVerify(exactly = 0) { client.readRecords<DistanceRecord>(any()) }
        }

    @Test
    fun `resolveTotal matches matching session intervals`() {
        val session = mockExerciseSession(t0, t1, "com.strava")
        val totals =
            listOf(
                DomainIntervalTotal(
                    startTime = t0,
                    endTime = t1,
                    originPackage = "com.strava",
                    value = 5200.0,
                ),
                DomainIntervalTotal(
                    startTime = Instant.parse("2026-05-02T00:00:00Z"),
                    endTime = Instant.parse("2026-05-02T01:00:00Z"),
                    originPackage = "com.strava",
                    value = 3000.0,
                ),
            )

        val resolved = reader.resolveTotal(session, totals)
        assertEquals(5200.0, resolved ?: 0.0, 0.01)
    }

    @Test
    fun `resolveTotal returns null if no overlapping interval`() {
        val session = mockExerciseSession(t0, t1, "com.strava")
        val totals =
            listOf(
                DomainIntervalTotal(
                    startTime = Instant.parse("2026-05-02T00:00:00Z"),
                    endTime = Instant.parse("2026-05-02T01:00:00Z"),
                    originPackage = "com.strava",
                    value = 3000.0,
                ),
            )

        val resolved = reader.resolveTotal(session, totals)
        assertNull(resolved)
    }
}
