package app.readylytics.health.domain.user

import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.domain.preferences.SettingsRepository
import app.readylytics.health.domain.repository.ScoringRepository
import app.readylytics.health.workers.WorkerScheduler
import io.mockk.coJustRun
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate

class UserUseCaseTest {
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val workerScheduler = mockk<WorkerScheduler>(relaxed = true)
    private val scoringRepository = mockk<ScoringRepository>(relaxed = true)
    private val useCase = UserUseCase(settingsRepo, workerScheduler, scoringRepository)
    private val birthday = LocalDate.of(1990, 6, 15)

    @Test
    fun `updateBirthday schedules exactly one historical recompute when max HR is automatic`() =
        runTest {
            every { settingsRepo.userPreferences } returns
                flowOf(UserPreferences(autoCalculateMaxHr = true))
            coJustRun { settingsRepo.updateBirthday(birthday) }
            coJustRun { settingsRepo.updateMaxHeartRate(any()) }
            coJustRun { scoringRepository.computeAndPersistDailySummary() }

            useCase.updateBirthday(birthday)

            verify(exactly = 1) { workerScheduler.scheduleResyncWorker(recomputeOnly = true) }
        }

    @Test
    fun `updateBirthday schedules exactly one historical recompute when max HR is manual`() =
        runTest {
            every { settingsRepo.userPreferences } returns
                flowOf(UserPreferences(autoCalculateMaxHr = false))
            coJustRun { settingsRepo.updateBirthday(birthday) }
            coJustRun { scoringRepository.computeAndPersistDailySummary() }

            useCase.updateBirthday(birthday)

            verify(exactly = 1) { workerScheduler.scheduleResyncWorker(recomputeOnly = true) }
        }
}
