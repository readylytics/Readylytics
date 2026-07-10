package app.readylytics.health.domain.sync

import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.domain.model.Result
import app.readylytics.health.domain.preferences.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthSyncUseCaseTest {
    private val dailySyncUseCase = mockk<DailySyncUseCase>(relaxed = true)
    private val resyncRangeUseCase = mockk<ResyncRangeUseCase>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)

    private val useCase = HealthSyncUseCase(
        dailySyncUseCase = dailySyncUseCase,
        resyncRangeUseCase = resyncRangeUseCase,
        settingsRepo = settingsRepo,
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
    fun sync_returnsResultProducedByDailySyncUseCase() = runTest {
        coEvery { dailySyncUseCase.run(any(), any()) } returns Result.failure("nope", "SYNC_ERROR")

        val result = useCase.sync()

        assertTrue(result is Result.Failure)
        assertEquals("SYNC_ERROR", result.code)
    }
}
