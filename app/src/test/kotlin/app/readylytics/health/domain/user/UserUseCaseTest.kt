package app.readylytics.health.domain.user

import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.model.Result
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.scoring.RecomputeToday
import app.readylytics.health.core.model.workers.WorkerScheduler
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UserUseCaseTest {
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val workerScheduler = mockk<WorkerScheduler>(relaxed = true)
    private val recomputeToday = mockk<RecomputeToday>(relaxed = true)
    private val testClock = Clock.fixed(Instant.parse("2026-08-27T00:00:00Z"), ZoneOffset.UTC)
    private val useCase = UserUseCase(settingsRepo, workerScheduler, recomputeToday, testClock)
    private val birthday = LocalDate.of(1990, 6, 15)

    @Test
    fun `calculateAge computes correct age from birth date using injected clock`() {
        val age = useCase.calculateAge(LocalDate.of(1995, 8, 27))
        assertEquals(31, age)
    }

    @Test
    fun `calculateAge computes correct age with explicit today date`() {
        val age = useCase.calculateAge(LocalDate.of(1995, 8, 27), today = LocalDate.of(2025, 8, 27))
        assertEquals(30, age)
    }

    @Test
    fun `updateBirthday schedules exactly one historical recompute when max HR is automatic`() =
        runTest {
            every { settingsRepo.userPreferences } returns
                flowOf(UserPreferences(autoCalculateMaxHr = true))
            coJustRun { settingsRepo.updateBirthday(birthday) }
            coJustRun { settingsRepo.updateMaxHeartRate(any()) }
            coEvery { recomputeToday.execute(any()) } returns Result.success(Unit)

            useCase.updateBirthday(birthday)

            coVerifyOrder {
                settingsRepo.updateBirthday(birthday)
                settingsRepo.updateMaxHeartRate(any())
                recomputeToday.execute(LocalDate.now(testClock))
                workerScheduler.scheduleResyncWorker(recomputeOnly = true)
            }
            coVerify(exactly = 1) { workerScheduler.scheduleResyncWorker(recomputeOnly = true) }
        }

    @Test
    fun `updateBirthday schedules exactly one historical recompute when max HR is manual`() =
        runTest {
            every { settingsRepo.userPreferences } returns
                flowOf(UserPreferences(autoCalculateMaxHr = false))
            coJustRun { settingsRepo.updateBirthday(birthday) }
            coEvery { recomputeToday.execute(any()) } returns Result.success(Unit)

            useCase.updateBirthday(birthday)

            coVerifyOrder {
                settingsRepo.updateBirthday(birthday)
                recomputeToday.execute(LocalDate.now(testClock))
                workerScheduler.scheduleResyncWorker(recomputeOnly = true)
            }
            coVerify(exactly = 0) { settingsRepo.updateMaxHeartRate(any()) }
            coVerify(exactly = 1) { workerScheduler.scheduleResyncWorker(recomputeOnly = true) }
        }

    @Test
    fun `updateBirthday rethrows CancellationException`() =
        runTest {
            coEvery { settingsRepo.updateBirthday(any()) } throws
                CancellationException("Job cancelled")

            assertFailsWith<CancellationException> {
                useCase.updateBirthday(birthday)
            }
        }

    @Test
    fun `calculateAndSetMaxHr rethrows CancellationException`() =
        runTest {
            every { settingsRepo.userPreferences } returns
                flow {
                    throw CancellationException("Job cancelled")
                }

            assertFailsWith<CancellationException> {
                useCase.calculateAndSetMaxHr()
            }
        }
}
