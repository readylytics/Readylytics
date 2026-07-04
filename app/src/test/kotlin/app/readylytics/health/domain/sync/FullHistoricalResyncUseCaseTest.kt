package app.readylytics.health.domain.sync

import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.domain.model.Result
import app.readylytics.health.domain.util.RetentionBounds
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals

class FullHistoricalResyncUseCaseTest {
    private val settingsRepo = mockk<SettingsRepository>()
    private val healthSyncUseCase = mockk<HealthSyncUseCase>()
    private val useCase = FullHistoricalResyncUseCase(settingsRepo, healthSyncUseCase)

    private val today = LocalDate.now(ZoneId.systemDefault())

    @Test
    fun `enabled retention resyncs from today minus retentionDays to today`() =
        runTest {
            every { settingsRepo.userPreferences } returns
                flowOf(UserPreferences(retentionDaysEnabled = true, retentionDays = 365))
            val startSlot = slot<LocalDate>()
            val endSlot = slot<LocalDate>()
            coEvery {
                healthSyncUseCase.resyncRange(capture(startSlot), capture(endSlot), any(), any())
            } returns Result.success(Unit)

            useCase.execute()

            assertEquals(today.minusDays(365), startSlot.captured)
            assertEquals(today, endSlot.captured)
        }

    @Test
    fun `disabled retention resyncs the full absolute-max window`() =
        runTest {
            every { settingsRepo.userPreferences } returns
                flowOf(UserPreferences(retentionDaysEnabled = false, retentionDays = 365))
            val startSlot = slot<LocalDate>()
            coEvery {
                healthSyncUseCase.resyncRange(capture(startSlot), any(), any(), any())
            } returns Result.success(Unit)

            useCase.execute()

            assertEquals(today.minusDays(RetentionBounds.ABSOLUTE_MAX_DAYS), startSlot.captured)
        }

    @Test
    fun `delegates failure from the underlying resync`() =
        runTest {
            every { settingsRepo.userPreferences } returns
                flowOf(UserPreferences(retentionDaysEnabled = true, retentionDays = 200))
            coEvery { healthSyncUseCase.resyncRange(any(), any(), any(), any()) } returns
                Result.failure("boom", "RESYNC_ERROR")

            val result = useCase.execute()

            assert(result is Result.Failure)
            coVerify { healthSyncUseCase.resyncRange(any(), any(), any(), any()) }
        }
}
