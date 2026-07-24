package app.readylytics.health.domain.sync

import app.readylytics.health.domain.preferences.UserPreferences
import app.readylytics.health.domain.repository.HealthConnectRepository
import app.readylytics.health.domain.repository.HealthConnectWindowTimeoutException
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * HC-002 regression lock: a Health Connect read that can't complete within its window budget must
 * surface as [HealthConnectWindowTimeoutException], never as a bare
 * [kotlinx.coroutines.TimeoutCancellationException] that a caller could mistake for cooperative
 * cancellation. [ResyncRangeUseCase]'s chunk-shrink/retry policy on top of this exception is
 * covered separately in `ResyncRangeUseCaseTest`.
 */
class HealthIngestionCoordinatorTimeoutTest {
    @Test
    fun `ingestWindow converts a Health Connect read timeout into a domain exception`() =
        runTest {
            val hcRepo = mockk<HealthConnectRepository>(relaxed = true)
            val healthIngestionStore = mockk<HealthIngestionStore>(relaxed = true)
            coEvery { hcRepo.readSleepSessions(any(), any()) } coAnswers {
                delay(200L)
                emptyList()
            }
            val coordinator = HealthIngestionCoordinator(hcRepo, healthIngestionStore)
            val windowStart = Instant.EPOCH
            val windowEnd = Instant.EPOCH.plusSeconds(3600)

            val exception =
                assertFailsWith<HealthConnectWindowTimeoutException> {
                    coordinator.ingestWindow(
                        windowStart = windowStart,
                        windowEnd = windowEnd,
                        prefs = UserPreferences(),
                        windowBudgetMs = 100L,
                    )
                }

            assertEquals(windowStart, exception.windowStart)
            assertEquals(windowEnd, exception.windowEnd)
        }
}
