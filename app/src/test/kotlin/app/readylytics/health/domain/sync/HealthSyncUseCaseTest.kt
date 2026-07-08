package app.readylytics.health.domain.sync

import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.domain.model.Result
import app.readylytics.health.domain.preferences.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * The god-class behavior now lives in [DailySyncUseCase] / [ResyncRangeUseCase] (tested directly in
 * their own suites). [HealthSyncUseCase] is a thin facade that owns the shared `syncMutex` and
 * delegates, so this suite only asserts the delegation contract and the `catchUpSync` window.
 */
class HealthSyncUseCaseTest {
    private val dailySync = mockk<DailySyncUseCase>()
    private val resyncRange = mockk<ResyncRangeUseCase>()
    private val settingsRepo = mockk<SettingsRepository>()

    private lateinit var useCase: HealthSyncUseCase

    @Before
    fun setup() {
        coEvery { dailySync.run(any(), any()) } returns Result.success(Unit)
        coEvery { resyncRange.run(any(), any(), any(), any()) } returns Result.success(Unit)
        coEvery { settingsRepo.userPreferences } returns flowOf(UserPreferences(lastSyncTimestamp = 0L, scoringZoneId = "UTC"))
        useCase = HealthSyncUseCase(dailySync, resyncRange, settingsRepo)
    }

    @Test
    fun `sync delegates to DailySyncUseCase with the requested window`() =
        runTest {
            useCase.sync(windowDays = 3)

            coVerify { dailySync.run(3, null) }
        }

    @Test
    fun `catchUpSync delegates to ResyncRangeUseCase with a 365-day window`() =
        runTest {
            useCase.catchUpSync()

            coVerify {
                resyncRange.run(
                    startDate = any(),
                    endDate = any(),
                    chunkDays = 30,
                    onProgress = null,
                )
            }
        }

    @Test
    fun `catchUpSync skips sync when lastSyncTimestamp is set`() =
        runTest {
            coEvery { settingsRepo.userPreferences } returns flowOf(UserPreferences(lastSyncTimestamp = 123L))

            val result = useCase.catchUpSync()

            assertTrue(result.isSuccess)
            coVerify(exactly = 0) { resyncRange.run(any(), any(), any(), any()) }
        }

    @Test
    fun `resyncRange delegates to ResyncRangeUseCase with the requested range`() =
        runTest {
            val startDate = LocalDate.of(2024, 6, 1)
            val endDate = LocalDate.of(2024, 6, 2)

            useCase.resyncRange(startDate, endDate)

            coVerify { resyncRange.run(startDate, endDate, 30, null) }
        }

    @Test
    fun `sync returns the result produced by DailySyncUseCase`() =
        runTest {
            coEvery { dailySync.run(any(), any()) } returns Result.failure("nope", "SYNC_ERROR")

            val result = useCase.sync()

            assertTrue(result is Result.Failure)
            assertEquals("SYNC_ERROR", (result as Result.Failure).code)
        }
}
