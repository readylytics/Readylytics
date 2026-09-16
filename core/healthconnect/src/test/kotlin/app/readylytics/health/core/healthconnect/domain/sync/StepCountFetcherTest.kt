package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.model.domain.model.DomainStepsRecord
import app.readylytics.health.core.model.domain.repository.HealthConnectRepository
import app.readylytics.health.core.model.domain.repository.ReadOutcome
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class StepCountFetcherTest {
    private val hcRepo = mockk<HealthConnectRepository>(relaxed = true)
    private val zoneId = ZoneId.of("UTC")
    private lateinit var fetcher: StepCountFetcher

    private val today = LocalDate.of(2026, 9, 14)

    @Before
    fun setup() {
        fetcher = StepCountFetcher(hcRepo)
    }

    @Test
    fun `fetchWindow with all devices populates days on Available read`() =
        runTest {
            coEvery { hcRepo.readSteps(any(), any()) } returns ReadOutcome.Available(5000L)

            val result = fetcher.fetchWindow(today, windowDays = 2, zoneId = zoneId, stepsDevice = null)

            assertEquals(2, result.size)
            assertEquals(5000L, result[today])
            assertEquals(5000L, result[today.minusDays(1)])
        }

    @Test
    fun `fetchWindow with all devices omits days when read is Denied or Unsupported`() =
        runTest {
            coEvery { hcRepo.readSteps(any(), any()) } returns ReadOutcome.Denied

            val result = fetcher.fetchWindow(today, windowDays = 2, zoneId = zoneId, stepsDevice = null)

            assertTrue(result.isEmpty())
        }

    @Test
    fun `fetchWindow with selected device zeroes days and sums matching records on Available`() =
        runTest {
            val record =
                DomainStepsRecord(
                    id = "step-1",
                    startTime = today.atStartOfDay(zoneId).plusHours(2).toInstant(),
                    endTime = today.atStartOfDay(zoneId).plusHours(3).toInstant(),
                    count = 3500L,
                    deviceName = "Pixel Watch",
                )
            coEvery { hcRepo.readStepsRecords(any(), any()) } returns ReadOutcome.Available(listOf(record))

            val result = fetcher.fetchWindow(today, windowDays = 3, zoneId = zoneId, stepsDevice = "Pixel Watch")

            assertEquals(3, result.size)
            assertEquals(3500L, result[today])
            assertEquals(0L, result[today.minusDays(1)])
            assertEquals(0L, result[today.minusDays(2)])
        }

    @Test
    fun `fetchWindow with selected device explicitly zeroes prior totals on Available empty records`() =
        runTest {
            coEvery { hcRepo.readStepsRecords(any(), any()) } returns ReadOutcome.Available(emptyList())

            val result = fetcher.fetchWindow(today, windowDays = 2, zoneId = zoneId, stepsDevice = "Pixel Watch")

            assertEquals(2, result.size)
            assertEquals(0L, result[today])
            assertEquals(0L, result[today.minusDays(1)])
        }

    @Test
    fun `fetchWindow with selected device omits days when read is Denied`() =
        runTest {
            coEvery { hcRepo.readStepsRecords(any(), any()) } returns ReadOutcome.Denied

            val result = fetcher.fetchWindow(today, windowDays = 2, zoneId = zoneId, stepsDevice = "Pixel Watch")

            assertTrue(result.isEmpty())
        }

    @Test
    fun `fetchRange with all devices omits days when read is Denied or Unsupported`() =
        runTest {
            val start = LocalDate.of(2026, 9, 1)
            val end = LocalDate.of(2026, 9, 3)
            coEvery { hcRepo.readDailyStepTotals(any(), any(), any()) } returns ReadOutcome.Denied

            val result = fetcher.fetchRange(start, end, chunkDays = 10, stepsDevice = null, zoneId = zoneId)

            assertTrue(result.isEmpty())
        }

    @Test
    fun `fetchRange with selected device omits days when read is Denied`() =
        runTest {
            val start = LocalDate.of(2026, 9, 1)
            val end = LocalDate.of(2026, 9, 3)
            coEvery { hcRepo.readStepsRecords(any(), any()) } returns ReadOutcome.Denied

            val result = fetcher.fetchRange(start, end, chunkDays = 10, stepsDevice = "Pixel Watch", zoneId = zoneId)

            assertTrue(result.isEmpty())
        }

    @Test
    fun `fetchWindow with selected device clips records starting outside requested window`() =
        runTest {
            val insideWindowRecord =
                DomainStepsRecord(
                    id = "step-inside",
                    startTime = today.atStartOfDay(zoneId).plusHours(2).toInstant(),
                    endTime = today.atStartOfDay(zoneId).plusHours(3).toInstant(),
                    count = 3000L,
                    deviceName = "Pixel Watch",
                )
            val outsideWindowRecord =
                DomainStepsRecord(
                    id = "step-outside",
                    startTime = today.minusDays(2).atStartOfDay(zoneId).plusHours(23).toInstant(),
                    endTime = today.minusDays(1).atStartOfDay(zoneId).plusHours(1).toInstant(),
                    count = 2000L,
                    deviceName = "Pixel Watch",
                )
            coEvery { hcRepo.readStepsRecords(any(), any()) } returns
                ReadOutcome.Available(listOf(insideWindowRecord, outsideWindowRecord))

            val result = fetcher.fetchWindow(today, windowDays = 2, zoneId = zoneId, stepsDevice = "Pixel Watch")

            assertEquals(2, result.size)
            assertEquals(3000L, result[today])
            assertEquals(0L, result[today.minusDays(1)])
            assertFalse(result.containsKey(today.minusDays(2)))
        }

    @Test
    fun `fetchRange with selected device deduplicates across chunk boundaries and clips to range`() =
        runTest {
            val start = LocalDate.of(2026, 9, 1)
            val end = LocalDate.of(2026, 9, 2)
            // Straddling record that might be returned in multiple chunks
            val straddlingRecord =
                DomainStepsRecord(
                    id = "step-straddle",
                    startTime = start.atStartOfDay(zoneId).plusHours(12).toInstant(),
                    endTime = start.plusDays(1).atStartOfDay(zoneId).plusHours(2).toInstant(),
                    count = 1500L,
                    deviceName = "Pixel Watch",
                )
            // Returned in both chunk 1 and chunk 2
            coEvery { hcRepo.readStepsRecords(any(), any()) } returns
                ReadOutcome.Available(listOf(straddlingRecord))

            val result = fetcher.fetchRange(start, end, chunkDays = 1, stepsDevice = "Pixel Watch", zoneId = zoneId)

            assertEquals(2, result.size)
            assertEquals(1500L, result[start])
            assertEquals(0L, result[end])
        }
}
