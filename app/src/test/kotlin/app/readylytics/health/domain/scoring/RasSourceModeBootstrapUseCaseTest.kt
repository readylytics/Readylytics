package app.readylytics.health.domain.scoring

import app.readylytics.health.data.local.dao.DailySummaryDao
import app.readylytics.health.data.preferences.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class RasSourceModeBootstrapUseCaseTest {
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val dailySummaryDao = mockk<DailySummaryDao>(relaxed = true)

    private lateinit var useCase: RasSourceModeBootstrapUseCase

    @Before
    fun setup() {
        useCase = RasSourceModeBootstrapUseCase(settingsRepo, dailySummaryDao)
    }

    @Test
    fun `delegates to settingsRepo with true when workout-only history exists`() =
        runTest {
            coEvery { dailySummaryDao.hasAnyWorkoutOnlyTrimpData() } returns true

            useCase()

            coVerify { settingsRepo.bootstrapRasSourceModeIfUnset(true) }
        }

    @Test
    fun `delegates to settingsRepo with false when no workout-only history exists`() =
        runTest {
            coEvery { dailySummaryDao.hasAnyWorkoutOnlyTrimpData() } returns false

            useCase()

            coVerify { settingsRepo.bootstrapRasSourceModeIfUnset(false) }
        }
}
