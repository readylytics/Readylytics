package app.readylytics.health.domain.sync

import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.domain.model.Result
import app.readylytics.health.domain.preferences.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthSyncUseCaseTest {
    private val dailySyncUseCase = mockk<DailySyncUseCase>(relaxed = true)
    private val resyncRangeUseCase = mockk<ResyncRangeUseCase>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val fixedClock = Clock.fixed(Instant.parse("2024-06-15T12:00:00Z"), ZoneId.of("UTC"))

    private val useCase = HealthSyncUseCase(
        dailySyncUseCase = dailySyncUseCase,
        resyncRangeUseCase = resyncRangeUseCase,
        settingsRepo = settingsRepo,
        clock = fixedClock,
    )

    @Test
    fun catchUpSync_whenLastSyncTimestampIsSet_skipsSync() = runTest {
        val prefs = UserPreferences(lastSyncTimestamp = 123456L)
        coEvery { settingsRepo.userPreferences } returns flowOf(prefs)

        val result = useCase.catchUpSync()

        assertTrue(result.isSuccess)
        coVerify(exactly = 0) { resyncRangeUseCase.run(any(), any(), any(), any()) }
    }

    @Test
    fun catchUpSync_whenLastSyncTimestampIsZero_runsHistoricalChunkedResync() = runTest {
        val prefs = UserPreferences(lastSyncTimestamp = 0L, scoringZoneId = "UTC")
        coEvery { settingsRepo.userPreferences } returns flowOf(prefs)
        coEvery { resyncRangeUseCase.run(any(), any(), any(), any()) } returns Result.success(Unit)

        val result = useCase.catchUpSync()

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) {
            resyncRangeUseCase.run(
                startDate = any(),
                endDate = any(),
                chunkDays = 30,
                onProgress = any(),
            )
        }
    }

    @Test
    fun catchUpSync_resolvesTodayFromTheInjectedClock_notTheRealSystemClock() = runTest {
        val historicalClock = Clock.fixed(Instant.parse("2019-01-10T08:00:00Z"), ZoneId.of("UTC"))
        val useCaseWithHistoricalClock = HealthSyncUseCase(
            dailySyncUseCase = dailySyncUseCase,
            resyncRangeUseCase = resyncRangeUseCase,
            settingsRepo = settingsRepo,
            clock = historicalClock,
        )
        val prefs = UserPreferences(lastSyncTimestamp = 0L, scoringZoneId = "UTC")
        coEvery { settingsRepo.userPreferences } returns flowOf(prefs)
        coEvery { resyncRangeUseCase.run(any(), any(), any(), any()) } returns Result.success(Unit)
        val endDateSlot = slot<LocalDate>()

        useCaseWithHistoricalClock.catchUpSync()

        coVerify {
            resyncRangeUseCase.run(
                startDate = any(),
                endDate = capture(endDateSlot),
                chunkDays = 30,
                onProgress = any(),
            )
        }
        assertEquals(LocalDate.of(2019, 1, 10), endDateSlot.captured)
    }

    @Test
    fun sync_delegatesToDailySyncUseCaseWithRequestedWindow() = runTest {
        coEvery { dailySyncUseCase.run(any(), any()) } returns Result.success(Unit)

        useCase.sync(windowDays = 3)

        coVerify { dailySyncUseCase.run(3, null) }
    }

    @Test
    fun resyncRange_delegatesToResyncRangeUseCaseWithRequestedRange() = runTest {
        val startDate = LocalDate.of(2024, 6, 1)
        val endDate = LocalDate.of(2024, 6, 2)
        coEvery { resyncRangeUseCase.run(any(), any(), any(), any()) } returns Result.success(Unit)

        useCase.resyncRange(startDate, endDate)

        coVerify { resyncRangeUseCase.run(startDate, endDate, 30, null) }
    }

    @Test
    fun recomputeRange_delegatesToResyncRangeUseCaseWithSkipIngestAndPrune() = runTest {
        // SCORE-007: recomputeRange must skip Health Connect re-ingestion entirely.
        val startDate = LocalDate.of(2024, 6, 1)
        val endDate = LocalDate.of(2024, 6, 2)
        coEvery {
            resyncRangeUseCase.run(any(), any(), any(), any(), skipIngestAndPrune = true)
        } returns Result.success(Unit)

        useCase.recomputeRange(startDate, endDate)

        coVerify {
            resyncRangeUseCase.run(startDate, endDate, 30, null, skipIngestAndPrune = true)
        }
    }

    @Test
    fun sync_returnsResultProducedByDailySyncUseCase() = runTest {
        coEvery { dailySyncUseCase.run(any(), any()) } returns Result.failure("nope", "SYNC_ERROR")

        val result = useCase.sync(windowDays = 3)

        assertTrue(result is Result.Failure)
        assertEquals("SYNC_ERROR", result.code)
    }

    @Test
    fun withSyncLock_returnsTheBlocksResult() = runTest {
        val result = useCase.withSyncLock { "value" }

        assertEquals("value", result)
    }

    @Test
    fun withSyncLock_serializesAgainstAConcurrentSync() = runTest {
        // SCORE-003: withSyncLock must share the same mutex sync()/resyncRange() use, so a caller
        // like the app-start baseline backfill can never run concurrently with a sync/resync.
        val syncStarted = CompletableDeferred<Unit>()
        val releaseSync = CompletableDeferred<Unit>()
        coEvery { dailySyncUseCase.run(any(), any()) } coAnswers {
            syncStarted.complete(Unit)
            releaseSync.await()
            Result.success(Unit)
        }
        val order = mutableListOf<String>()

        val syncJob = launch {
            useCase.sync(windowDays = 3)
            order += "sync-done"
        }
        syncStarted.await()

        val lockJob =
            launch {
                useCase.withSyncLock { order += "lock-acquired" }
            }
        advanceUntilIdle()
        assertTrue(order.isEmpty(), "withSyncLock must not proceed while sync() holds the mutex")

        releaseSync.complete(Unit)
        syncJob.join()
        lockJob.join()

        assertEquals(listOf("sync-done", "lock-acquired"), order)
    }
}
