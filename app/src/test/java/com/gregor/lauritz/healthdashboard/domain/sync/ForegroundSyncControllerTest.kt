package com.gregor.lauritz.healthdashboard.domain.sync

import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.preferences.SyncPreference
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundSyncControllerTest {
    private val settingsRepo = mockk<SettingsRepository>()
    private val syncUseCase = mockk<HealthSyncUseCase>()
    private val controller = ForegroundSyncController(settingsRepo, syncUseCase)

    @Test
    fun `evaluateAndSync should not run multiple syncs concurrently`() =
        runTest {
            val prefs =
                UserPreferences(
                    syncPreference = SyncPreference.ALWAYS,
                    lastSyncTimestamp = 123456789L, // Non-zero to trigger sync() instead of catchUpSync()
                )

            coEvery { settingsRepo.userPreferences } returns flowOf(prefs)
            coEvery { syncUseCase.sync(any(), any()) } coAnswers {
                delay(100) // Simulate long running sync
                com.gregor.lauritz.healthdashboard.domain.model.Result
                    .Success(Unit)
            }

            // Launch two syncs
            launch { controller.evaluateAndSync() }
            launch { controller.evaluateAndSync() }

            advanceTimeBy(150)

            // Verify sync() was only called once because the second one should have been blocked by the mutex (tryLock)
            coVerify(exactly = 1) { syncUseCase.sync(any(), any()) }
        }

    @Test
    fun `evaluateAndSync uses windowDays=1 when last sync was today`() =
        runTest {
            val todayMs =
                LocalDate.now(ZoneId.systemDefault())
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            val prefs =
                UserPreferences(
                    syncPreference = SyncPreference.ALWAYS,
                    lastSyncTimestamp = todayMs,
                )

            coEvery { settingsRepo.userPreferences } returns flowOf(prefs)
            coEvery { syncUseCase.sync(any(), any()) } returns
                com.gregor.lauritz.healthdashboard.domain.model.Result.Success(Unit)

            controller.evaluateAndSync()

            coVerify(exactly = 1) { syncUseCase.sync(windowDays = 1, onProgress = any()) }
        }

    @Test
    fun `evaluateAndSync uses daysSinceLastSync+1 when last sync was multiple days ago`() =
        runTest {
            val threeDaysAgoMs =
                LocalDate.now(ZoneId.systemDefault())
                    .minusDays(3)
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            val prefs =
                UserPreferences(
                    syncPreference = SyncPreference.ALWAYS,
                    lastSyncTimestamp = threeDaysAgoMs,
                )

            coEvery { settingsRepo.userPreferences } returns flowOf(prefs)
            coEvery { syncUseCase.sync(any(), any()) } returns
                com.gregor.lauritz.healthdashboard.domain.model.Result.Success(Unit)

            controller.evaluateAndSync()

            coVerify(exactly = 1) { syncUseCase.sync(windowDays = 4, onProgress = any()) }
        }
}
