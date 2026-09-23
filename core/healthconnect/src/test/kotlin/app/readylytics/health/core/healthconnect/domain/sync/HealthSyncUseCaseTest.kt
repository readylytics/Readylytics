package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.model.domain.sync.*
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.model.Result
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
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
    private val coordinator =
        object : HealthMutationCoordinator {
            override suspend fun <T> withMutation(block: suspend () -> T): T = block()

            override suspend fun <T> withMaintenance(operationId: String, block: suspend () -> T): T = block()
        }
    private val fixedClock = Clock.fixed(Instant.parse("2024-06-15T12:00:00Z"), ZoneId.of("UTC"))

    private val useCase = HealthSyncUseCase(
        dailySyncUseCase = dailySyncUseCase,
        resyncRangeUseCase = resyncRangeUseCase,
        settingsRepo = settingsRepo,
        clock = fixedClock,
        coordinator = coordinator,
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
            coordinator = coordinator,
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

}
