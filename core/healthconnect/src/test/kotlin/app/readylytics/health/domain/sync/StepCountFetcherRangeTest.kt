package app.readylytics.health.domain.sync

import app.readylytics.health.domain.repository.HealthConnectRepository
import io.mockk.coEvery
import io.mockk.firstArg
import io.mockk.mockk
import io.mockk.secondArg
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals

/**
 * HC-003 regression lock: the "all devices" step range fetch must issue one grouped-by-day
 * aggregate call per chunk (via [HealthConnectRepository.readDailyStepTotals]) rather than one
 * aggregate call per calendar day.
 */
class StepCountFetcherRangeTest {
    private val hcRepo = mockk<HealthConnectRepository>(relaxed = true)
    private val fetcher = StepCountFetcher(hcRepo)
    private val zoneId: ZoneId = ZoneId.of("UTC")

    @Test
    fun `fetchRange with no selected device issues one grouped call per chunk`() =
        runTest {
            val startDate = LocalDate.of(2024, 1, 1)
            val endDate = LocalDate.of(2024, 1, 25)
            val chunkDays = 10

            val requestedWindows = mutableListOf<Pair<Instant, Instant>>()
            coEvery { hcRepo.readDailyStepTotals(any(), any(), any()) } coAnswers {
                val from = firstArg<Instant>()
                val to = secondArg<Instant>()
                requestedWindows += from to to
                mapOf(from.atZone(zoneId).toLocalDate() to 1_000L)
            }

            val result = fetcher.fetchRange(startDate, endDate, chunkDays, stepsDevice = null, zoneId = zoneId)

            // ceil(25 days / 10-day chunks) = 3 calls, not one per day (25).
            assertEquals(3, requestedWindows.size)
            assertEquals(
                listOf(
                    startDate.atStartOfDay(zoneId).toInstant() to startDate.plusDays(10).atStartOfDay(zoneId).toInstant(),
                    startDate.plusDays(10).atStartOfDay(zoneId).toInstant() to
                        startDate.plusDays(20).atStartOfDay(zoneId).toInstant(),
                    startDate.plusDays(20).atStartOfDay(zoneId).toInstant() to
                        endDate.plusDays(1).atStartOfDay(zoneId).toInstant(),
                ),
                requestedWindows,
            )
            assertEquals(1_000L, result[startDate])
        }
}
