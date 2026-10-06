package app.readylytics.health.core.healthconnect.data.healthconnect

import app.readylytics.health.core.model.domain.repository.ReadOutcome
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.response.ReadRecordsResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.coEvery
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals

/**
 * HC-001: `discoverDevices` must aggregate HR/HRV device names by streaming pages (via
 * readHeartRateSamplesPaged/readHrvSamplesPaged) rather than materializing every sample into one
 * list first. This test proves the *behavior* survives the switch across multiple pages; the
 * memory-boundedness itself is structural (no `mutableListOf<T>` accumulation across pages in the
 * streaming path -- verified by code inspection of readAllPagesStreaming).
 */
class HealthConnectRepositoryImplDiscoverDevicesTest {
    private val context = mockk<Context>(relaxed = true)
    private val client = mockk<HealthConnectClient>(relaxed = true)
    private lateinit var repo: HealthConnectRepositoryImpl

    private fun emptyResponse() =
        mockk<ReadRecordsResponse<Record>> {
            every { records } returns emptyList()
            every { pageToken } returns null
        }

    private fun hrRecord(deviceName: String) =
        mockk<HeartRateRecord>(relaxed = true) {
            every { metadata.id } returns "hr-$deviceName"
            every { metadata.device?.model } returns deviceName
            every { metadata.dataOrigin.packageName } returns "com.example.$deviceName"
            every { samples } returns emptyList()
        }

    private fun hrvRecord(deviceName: String) =
        mockk<HeartRateVariabilityRmssdRecord>(relaxed = true) {
            every { metadata.id } returns "hrv-$deviceName"
            every { metadata.device?.model } returns deviceName
            every { metadata.dataOrigin.packageName } returns "com.example.$deviceName"
            every { time } returns Instant.parse("2026-08-20T00:00:00Z")
            every { heartRateVariabilityMillis } returns 40.0
        }

    @Before
    fun setup() {
        mockkObject(HealthConnectClient)
        every { HealthConnectClient.getSdkStatus(any()) } returns HealthConnectClient.SDK_AVAILABLE

        // Default: every other record type returns one empty page.
        coEvery { client.readRecords<Record>(any()) } returns emptyResponse()

        // Heart rate: two pages, two distinct devices, chained by pageToken.
        coEvery {
            client.readRecords<Record>(match { it.recordType == HeartRateRecord::class && it.pageToken == null })
        } returns
            mockk {
                every { records } returns listOf(hrRecord("watch-a"))
                every { pageToken } returns "hr-page-2"
            }
        coEvery {
            client.readRecords<Record>(match { it.recordType == HeartRateRecord::class && it.pageToken == "hr-page-2" })
        } returns
            mockk {
                every { records } returns listOf(hrRecord("watch-b"))
                every { pageToken } returns null
            }

        // HRV: two pages, two distinct devices.
        coEvery {
            client.readRecords<Record>(
                match { it.recordType == HeartRateVariabilityRmssdRecord::class && it.pageToken == null },
            )
        } returns
            mockk {
                every { records } returns listOf(hrvRecord("band-a"))
                every { pageToken } returns "hrv-page-2"
            }
        coEvery {
            client.readRecords<Record>(
                match { it.recordType == HeartRateVariabilityRmssdRecord::class && it.pageToken == "hrv-page-2" },
            )
        } returns
            mockk {
                every { records } returns listOf(hrvRecord("band-b"))
                every { pageToken } returns null
            }

        val ioDispatcher = Dispatchers.Unconfined
        val stepRecordReader =
            StepRecordReader(context = context, ioDispatcher = ioDispatcher, client = client)
        val intervalTotalsReader =
            IntervalTotalsReader(context = context, ioDispatcher = ioDispatcher, client = client)
        repo =
            HealthConnectRepositoryImpl(
                context = context,
                ioDispatcher = ioDispatcher,
                stepRecordReader = stepRecordReader,
                intervalTotalsReader = intervalTotalsReader,
                clock = Clock.fixed(Instant.parse("2026-08-31T12:00:00Z"), ZoneId.of("UTC")),
                client = client,
            )
        coEvery { client.permissionController.getGrantedPermissions() } returns repo.allPermissions
    }

    @Test
    fun lateDistanceDenialDiscardsSuccessfulPage() = runTest { assertLateIntervalDenial(false) }

    @Test
    fun lateElevationDenialDiscardsSuccessfulPage() = runTest { assertLateIntervalDenial(true) }

    @Test
    fun lateIntervalCancellationPropagates() = runTest {
        val failure = kotlinx.coroutines.CancellationException("cancel second page")
        val thrown = kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> {
            assertLateIntervalDenial(false, failure)
        }
        assertEquals(failure.message, thrown.message)
    }

    @Test
    fun lateIntervalTransientFailurePropagates() = runTest {
        val failure = IllegalStateException("provider failed second page")
        val thrown = kotlin.test.assertFailsWith<IllegalStateException> {
            assertLateIntervalDenial(true, failure)
        }
        assertEquals(failure.message, thrown.message)
    }

    private suspend fun assertLateIntervalDenial(
        isElevation: Boolean,
        failure: Exception = SecurityException("revoked"),
    ) {
        val start = Instant.parse("2026-08-20T00:00:00Z")
        val end = start.plusSeconds(3600)
        val session = mockk<ExerciseSessionRecord>(relaxed = true) {
            every { metadata.id } returns "session"
            every { metadata.dataOrigin.packageName } returns "writer"
            every { startTime } returns start
            every { endTime } returns end
            every { exerciseRouteResult } returns ExerciseRouteResult.NoData()
        }
        coEvery { client.readRecords<Record>(match { it.recordType == session::class }) } returns mockk {
            every { records } returns listOf(session)
            every { pageToken } returns null
        }
        // Match the SDK class, since MockK may provide a subclass.
        coEvery { client.readRecords<Record>(match {
            it.recordType == ExerciseSessionRecord::class
        }) } returns mockk {
            every { records } returns listOf(session)
            every { pageToken } returns null
        }
        coEvery { client.readRecord(ExerciseSessionRecord::class, "session") } returns mockk {
            every { record } returns session
        }
        val type: kotlin.reflect.KClass<out Record> = if (isElevation) ElevationGainedRecord::class
            else DistanceRecord::class
        val interval: Record = if (isElevation) mockk<ElevationGainedRecord>(relaxed = true) {
            every { elevation } returns Length.meters(50.0)
            every { startTime } returns start
            every { endTime } returns end
            every { metadata.dataOrigin.packageName } returns "writer"
        } else mockk<DistanceRecord>(relaxed = true) {
            every { distance } returns Length.meters(500.0)
            every { startTime } returns start
            every { endTime } returns end
            every { metadata.dataOrigin.packageName } returns "writer"
        }
        coEvery { client.readRecords<Record>(match { it.recordType == type && it.pageToken == null }) } returns mockk {
            every { records } returns listOf(interval)
            every { pageToken } returns "denied-page"
        }
        coEvery {
            client.readRecords<Record>(match { it.recordType == type && it.pageToken == "denied-page" })
        } throws failure
        val outcome = repo.readExerciseSessions(start, end, includeDetails = true)
        val workout = (outcome as ReadOutcome.Available).data.single()
        kotlin.test.assertNull(if (isElevation) workout.elevationGainMeters else workout.totalDistanceMeters)
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    @Test
    fun standaloneRepositoryReadKeepsFiveAttempts() = runTest {
        val failure = object : java.io.IOException("rate limit") {}
        var sdkCalls = 0
        coEvery { client.readRecords<Record>(any()) } coAnswers { sdkCalls++; throw failure }
        val thrown = kotlin.test.assertFailsWith<java.io.IOException> {
            repo.readSleepSessions(Instant.EPOCH, Instant.EPOCH.plusSeconds(3600), retryScope = null)
        }
        assertEquals(5, sdkCalls)
        kotlin.test.assertSame(failure, thrown)
    }

    @Test
    fun standaloneRepositoryCancellationEscapesUnchanged() = runTest {
        val cancellation = kotlinx.coroutines.CancellationException("cancel")
        var sdkCalls = 0
        coEvery { client.readRecords<Record>(any()) } coAnswers { sdkCalls++; throw cancellation }
        val thrown = kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> {
            repo.readSleepSessions(Instant.EPOCH, Instant.EPOCH.plusSeconds(3600), retryScope = null)
        }
        assertEquals(1, sdkCalls)
        kotlin.test.assertSame(cancellation, thrown)
    }

    @Test
    fun sharedScopeBoundsActualRepositorySdkReadsAcrossNineBulkReaders() = runTest {
        val failure = object : java.io.IOException("rate limit") {}
        var sdkCalls = 0
        coEvery { client.readRecords<Record>(any()) } coAnswers {
            sdkCalls++
            kotlinx.coroutines.yield()
            throw failure
        }
        val coordinator = app.readylytics.health.core.healthconnect.domain.sync.HealthIngestionCoordinator(
            repo,
            mockk<app.readylytics.health.core.model.domain.sync.HealthIngestionStore>(relaxed = true),
            app.readylytics.health.core.healthconnect.domain.sync.FakeScanStagingStore(),
        )
        val thrown = kotlin.test.assertFailsWith<java.io.IOException> {
            coordinator.ingestWindow(Instant.EPOCH, Instant.EPOCH.plusSeconds(3600),
                app.readylytics.health.core.model.domain.preferences.UserPreferences())
        }
        kotlin.test.assertTrue(sdkCalls <= 5, "SDK calls: $sdkCalls")
        kotlin.test.assertSame(failure, thrown)
        kotlin.test.assertFalse((thrown as Throwable) is
            app.readylytics.health.core.model.domain.repository.HealthConnectWindowTimeoutException)
    }

    @Test
    fun pageConsumerFailureIsNeverRetriedByRepositoryScope() = runTest {
        val failure = object : java.io.IOException("consumer failed") {}
        var consumers = 0
        val scope = app.readylytics.health.core.healthconnect.domain.sync.ReadRetryBudget(delayFn = {})
        val thrown = kotlin.test.assertFailsWith<java.io.IOException> {
            repo.readHeartRateSamplesPaged(Instant.EPOCH, Instant.EPOCH.plusSeconds(3600), retryScope = scope) { _, _ ->
                consumers++
                throw failure
            }
        }
        kotlin.test.assertSame(failure, thrown)
        assertEquals(1, consumers)
        assertEquals(0, scope.attemptsUsed)
    }

    @Test
    fun `discoverDevices aggregates device names across multiple HR and HRV pages`() =
        runTest {
            val devices = repo.discoverDevices(windowDays = 2)

            assertEquals(listOf("band-a", "band-b", "watch-a", "watch-b"), devices)
        }
}
