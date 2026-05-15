package com.gregor.lauritz.healthdashboard.domain.sync

import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.preferences.SyncPreference
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
import com.gregor.lauritz.healthdashboard.data.sync.HealthSyncUseCase
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundSyncControllerTest {
    private val settingsRepo = mockk<SettingsRepository>()
    private val syncUseCase = mockk<HealthSyncUseCase>()
    private val controller = ForegroundSyncController(mockk(relaxed = true), settingsRepo, syncUseCase)

    @Test
    fun `evaluateAndSync should not run multiple syncs concurrently`() =
        runTest {
            val prefs =
                UserPreferences(
                    syncPreference = SyncPreference.ALWAYS,
                    lastSyncTimestamp = 123456789L, // Non-zero to trigger sync() instead of catchUpSync()
                )

            coEvery { settingsRepo.userPreferences } returns flowOf(prefs)
            coEvery { syncUseCase.sync() } coAnswers {
                delay(100) // Simulate long running sync
                Result.success(Unit)
            }

            // Launch two syncs
            launch { controller.evaluateAndSync() }
            launch { controller.evaluateAndSync() }

            advanceTimeBy(150)

            // Verify sync() was only called once because the second one should have been blocked by the mutex (tryLock)
            coVerify(exactly = 1) { syncUseCase.sync() }
        }
}
