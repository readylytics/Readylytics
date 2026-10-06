package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.model.domain.repository.ReadRetryScope
import app.readylytics.health.core.model.domain.sync.*
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.HealthConnectRepository
import app.readylytics.health.core.model.domain.repository.HealthConnectWindowTimeoutException
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
    fun sharedBudgetStopsAfterFiveCalls() = runTest {
        val hcRepo = mockk<HealthConnectRepository>(relaxed = true)
        val providerFailure = object : java.io.IOException("rate limit") {}
        var readRecordsCalls = 0
        suspend fun readSdk(scope: ReadRetryScope?): Nothing =
            retryWithBackoff(delayFn = {}, budget = scope) {
                readRecordsCalls++
                kotlinx.coroutines.yield()
                throw providerFailure
            }
        coEvery { hcRepo.hasVo2MaxPermission() } returns true
        coEvery { hcRepo.readSleepSessions(any(), any(), any()) } coAnswers { readSdk(thirdArg()) }
        coEvery { hcRepo.readExerciseSessionsWithCompletion(any(), any(), any()) } coAnswers {
            app.readylytics.health.core.model.domain.repository.ExerciseSessionRead(
                hcRepo.readExerciseSessions(firstArg(), secondArg(), true, thirdArg()),
            )
        }

        coEvery { hcRepo.readExerciseSessions(any(), any(), any(), any()) } coAnswers { readSdk(arg(3)) }
        coEvery { hcRepo.readWeightRecords(any(), any(), any()) } coAnswers { readSdk(thirdArg()) }
        coEvery { hcRepo.readBodyFatRecords(any(), any(), any()) } coAnswers { readSdk(thirdArg()) }
        coEvery { hcRepo.readBloodPressureRecords(any(), any(), any()) } coAnswers { readSdk(thirdArg()) }
        coEvery { hcRepo.readOxygenSaturationRecords(any(), any(), any()) } coAnswers { readSdk(thirdArg()) }
        coEvery { hcRepo.readBodyTemperatureRecords(any(), any(), any()) } coAnswers { readSdk(thirdArg()) }
        coEvery { hcRepo.readStepsRecords(any(), any(), any()) } coAnswers { readSdk(thirdArg()) }
        coEvery { hcRepo.readVo2MaxRecords(any(), any(), any()) } coAnswers { readSdk(thirdArg()) }
        val coordinator = HealthIngestionCoordinator(
            hcRepo, mockk<HealthIngestionStore>(relaxed = true), FakeScanStagingStore(),
        )
        val thrown = assertFailsWith<java.io.IOException> {
            coordinator.ingestWindow(Instant.EPOCH, Instant.EPOCH.plusSeconds(3600), UserPreferences())
        }
        kotlin.test.assertTrue(readRecordsCalls <= 5, "SDK calls: $readRecordsCalls")
        kotlin.test.assertSame(providerFailure, thrown)
        kotlin.test.assertFalse((thrown as Throwable) is HealthConnectWindowTimeoutException)
    }

    @Test
    fun standaloneReadKeepsOwnRetry() = runTest {
        val providerFailure = object : java.io.IOException("rate limit") {}
        var calls = 0
        val thrown = assertFailsWith<java.io.IOException> {
            retryWithBackoff(delayFn = {}) { calls++; throw providerFailure }
        }
        assertEquals(5, calls)
        kotlin.test.assertSame(providerFailure, thrown)
        val cancellation = kotlinx.coroutines.CancellationException("cancel")
        kotlin.test.assertSame(cancellation, assertFailsWith<kotlinx.coroutines.CancellationException> {
            retryWithBackoff(delayFn = {}) { throw cancellation }
        })
    }

    @Test
    fun `ingestWindow converts a Health Connect read timeout into a domain exception`() =
        runTest {
            val hcRepo = mockk<HealthConnectRepository>(relaxed = true)
            val healthIngestionStore = mockk<HealthIngestionStore>(relaxed = true)
            coEvery { hcRepo.readSleepSessions(any(), any(), any()) } coAnswers {
                delay(200L)
                app.readylytics.health.core.model.domain.repository.ReadOutcome.Available(emptyList())
            }
            val coordinator = HealthIngestionCoordinator(hcRepo, healthIngestionStore, FakeScanStagingStore())
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
