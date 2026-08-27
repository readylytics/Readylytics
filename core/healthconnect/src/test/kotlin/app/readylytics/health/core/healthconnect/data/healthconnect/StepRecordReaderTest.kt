package app.readylytics.health.core.healthconnect.data.healthconnect

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.aggregate.AggregationResultGroupedByPeriod
import androidx.health.connect.client.records.StepsRecord
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class StepRecordReaderTest {
    private val client = mockk<HealthConnectClient>(relaxed = true)
    private val ioDispatcher = Dispatchers.Unconfined
    private lateinit var reader: StepRecordReader

    private val t0 = Instant.parse("2026-05-01T00:00:00Z")
    private val t1 = Instant.parse("2026-05-02T00:00:00Z")
    private val zoneUtc = ZoneId.of("UTC")

    @Before
    fun setup() {
        reader =
            StepRecordReader(
                context = mockk(relaxed = true),
                ioDispatcher = ioDispatcher,
            ).apply {
                clientOverride = client
            }
    }

    private fun mockStepsRecord(id: String, count: Long, start: Instant, end: Instant): StepsRecord =
        mockk(relaxed = true) {
            every { metadata.id } returns id
            every { metadata.dataOrigin.packageName } returns "app.test"
            every { metadata.device?.model } returns "TestWatch"
            every { this@mockk.count } returns count
            every { startTime } returns start
            every { endTime } returns end
        }

    @Test
    fun `readSteps returns total count on success`() =
        runTest {
            val aggResult = mockk<AggregationResult> {
                every { get(StepsRecord.COUNT_TOTAL) } returns 5432L
            }
            coEvery { client.aggregate(any()) } returns aggResult

            val result = reader.readSteps(t0, t1)
            assertEquals(5432L, result)
        }

    @Test
    fun `readSteps returns 0 on security exception`() =
        runTest {
            coEvery { client.aggregate(any()) } throws SecurityException("Permission denied")

            val result = reader.readSteps(t0, t1)
            assertEquals(0L, result)
        }

    @Test
    fun `readSteps rethrows non-security error`() =
        runTest {
            coEvery { client.aggregate(any()) } throws IOException("Network error")

            assertThrows(IOException::class.java) {
                kotlinx.coroutines.runBlocking { reader.readSteps(t0, t1) }
            }
        }

    @Test
    fun `readDailyStepTotals returns grouped map on success`() =
        runTest {
            val groupResult = mockk<AggregationResult> {
                every { get(StepsRecord.COUNT_TOTAL) } returns 8500L
            }
            val group = mockk<AggregationResultGroupedByPeriod> {
                every { startTime } returns LocalDateTime.of(2026, 5, 1, 0, 0)
                every { result } returns groupResult
            }
            coEvery {
                client.aggregateGroupByPeriod(any())
            } returns listOf(group)

            val result = reader.readDailyStepTotals(t0, t1, zoneUtc)
            assertEquals(1, result.size)
            assertEquals(8500L, result[LocalDate.of(2026, 5, 1)])
        }

    @Test
    fun `readDailyStepTotals falls back to per-day on UnsupportedOperationException`() =
        runTest {
            coEvery {
                client.aggregateGroupByPeriod(any())
            } throws UnsupportedOperationException("Group by not supported")

            val aggResult = mockk<AggregationResult> {
                every { get(StepsRecord.COUNT_TOTAL) } returns 3000L
            }
            coEvery { client.aggregate(any()) } returns aggResult

            val result = reader.readDailyStepTotals(t0, t1, zoneUtc)
            assertEquals(3000L, result[LocalDate.of(2026, 5, 1)])
        }

    @Test
    fun `readDailyStepTotals returns empty map on security exception`() =
        runTest {
            coEvery { client.aggregateGroupByPeriod(any()) } throws SecurityException("No perm")

            val result = reader.readDailyStepTotals(t0, t1, zoneUtc)
            assertTrue(result.isEmpty())
        }

    @Test
    fun `readStepsRecords returns mapped domain records on success`() =
        runTest {
            val mockRecord = mockStepsRecord("step-1", 1200L, t0, t1)
            coEvery {
                client.readRecords<StepsRecord>(any())
            } returns mockk {
                every { records } returns listOf(mockRecord)
                every { pageToken } returns null
            }

            val records = reader.readStepsRecords(t0, t1)
            assertEquals(1, records.size)
            assertEquals("step-1", records.first().id)
            assertEquals(1200L, records.first().count)
        }

    @Test
    fun `readStepsRecords returns empty list on security exception`() =
        runTest {
            coEvery {
                client.readRecords<StepsRecord>(any())
            } throws SecurityException("Permission denied")

            val records = reader.readStepsRecords(t0, t1)
            assertTrue(records.isEmpty())
        }
}
