package app.readylytics.health.core.healthconnect.data.healthconnect

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
        every { HealthConnectClient.getOrCreate(any()) } returns client
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
            StepRecordReader(context = context, ioDispatcher = ioDispatcher)
        val intervalTotalsReader =
            IntervalTotalsReader(context = context, ioDispatcher = ioDispatcher)
        repo =
            HealthConnectRepositoryImpl(
                context = context,
                ioDispatcher = ioDispatcher,
                stepRecordReader = stepRecordReader,
                intervalTotalsReader = intervalTotalsReader,
                clock = Clock.fixed(Instant.parse("2026-08-31T12:00:00Z"), ZoneId.of("UTC")),
            )
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    @Test
    fun `discoverDevices aggregates device names across multiple HR and HRV pages`() =
        runTest {
            val devices = repo.discoverDevices(windowDays = 2)

            assertEquals(listOf("band-a", "band-b", "watch-a", "watch-b"), devices)
        }
}
