package app.readylytics.health.domain.sync

import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.preferences.SyncPreference
import app.readylytics.health.data.preferences.UserPreferences
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundSyncControllerTest {
    private val settingsRepo = mockk<SettingsRepository>()
    private val syncUseCase = mockk<HealthSyncUseCase>()
    private val workerScheduler = mockk<app.readylytics.health.workers.WorkerScheduler>(relaxed = true)
    private val controller = ForegroundSyncController(settingsRepo, syncUseCase, dagger.Lazy { workerScheduler })

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
                app.readylytics.health.domain.model.Result
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
            val zone = ZoneId.systemDefault()
            val todayMs =
                LocalDate
                    .now(zone)
                    .atStartOfDay(zone)
                    .toInstant()
                    .toEpochMilli()
            val prefs =
                UserPreferences(
                    syncPreference = SyncPreference.ALWAYS,
                    lastSyncTimestamp = todayMs,
                )

            coEvery { settingsRepo.userPreferences } returns flowOf(prefs)
            coEvery { syncUseCase.sync(any(), any()) } returns
                app.readylytics.health.domain.model.Result
                    .Success(Unit)

            controller.evaluateAndSync()

            coVerify(exactly = 1) { syncUseCase.sync(windowDays = 1, onProgress = any()) }
        }

    @Test
    fun `evaluateAndSync uses daysSinceLastSync+1 when last sync was multiple days ago`() =
        runTest {
            val zone = ZoneId.systemDefault()
            val threeDaysAgoMs =
                LocalDate
                    .now(zone)
                    .minusDays(3)
                    .atStartOfDay(zone)
                    .toInstant()
                    .toEpochMilli()
            val prefs =
                UserPreferences(
                    syncPreference = SyncPreference.ALWAYS,
                    lastSyncTimestamp = threeDaysAgoMs,
                )

            coEvery { settingsRepo.userPreferences } returns flowOf(prefs)
            coEvery { syncUseCase.sync(any(), any()) } returns
                app.readylytics.health.domain.model.Result
                    .Success(Unit)

            controller.evaluateAndSync()

            coVerify(exactly = 1) { syncUseCase.sync(windowDays = 4, onProgress = any()) }
        }

    @Test
    fun `triggerDailySync propagates coroutine cancellation`() =
        runTest {
            coEvery { syncUseCase.sync(windowDays = 1, onProgress = any()) } throws
                CancellationException("cancelled")

            assertFailsWith<CancellationException> {
                controller.triggerDailySync()
            }
        }

    @Test
    fun `evaluateAndSync should not run when sync preference is NEVER`() =
        runTest {
            val prefs = UserPreferences(syncPreference = SyncPreference.NEVER)
            coEvery { settingsRepo.userPreferences } returns flowOf(prefs)

            controller.evaluateAndSync()

            coVerify(exactly = 0) { syncUseCase.sync(any(), any()) }
            coVerify(exactly = 0) { syncUseCase.catchUpSync(any()) }
        }

    @Test
    fun `evaluateAndSync should sync when sync preference is BY_TIME and interval met`() =
        runTest {
            val lastSyncMs = System.currentTimeMillis() - 4 * 3600_000L // 4 hours ago
            val prefs =
                UserPreferences(
                    syncPreference = SyncPreference.BY_TIME,
                    syncIntervalHours = 2, // 2 hour interval
                    lastSyncTimestamp = lastSyncMs,
                )
            coEvery { settingsRepo.userPreferences } returns flowOf(prefs)
            coEvery { syncUseCase.sync(any(), any()) } returns
                app.readylytics.health.domain.model.Result
                    .Success(Unit)

            controller.evaluateAndSync()

            coVerify(exactly = 1) { syncUseCase.sync(any(), any()) }
        }

    @Test
    fun `evaluateAndSync should not sync when sync preference is BY_TIME and interval not met`() =
        runTest {
            val lastSyncMs = System.currentTimeMillis() - 1 * 3600_000L // 1 hour ago
            val prefs =
                UserPreferences(
                    syncPreference = SyncPreference.BY_TIME,
                    syncIntervalHours = 2, // 2 hour interval
                    lastSyncTimestamp = lastSyncMs,
                )
            coEvery { settingsRepo.userPreferences } returns flowOf(prefs)

            controller.evaluateAndSync()

            coVerify(exactly = 0) { syncUseCase.sync(any(), any()) }
        }

    @Test
    fun `triggerImmediateSync executes catchUpSync and propagates progress`() =
        runTest {
            coEvery { syncUseCase.catchUpSync(any()) } coAnswers {
                val progress = firstArg<(Int, Int) -> Unit>()
                progress(1, 5)
                kotlinx.coroutines.yield()
                app.readylytics.health.domain.model.Result
                    .Success(Unit)
            }

            val observedProgresses = mutableListOf<RecalcProgress?>()
            val job =
                launch {
                    controller.recalcProgress.collect { observedProgresses.add(it) }
                }
            runCurrent()

            controller.triggerImmediateSync()
            runCurrent()

            val progressList = observedProgresses.filterNotNull()
            kotlin.test.assertTrue(progressList.isNotEmpty())
            kotlin.test.assertEquals(RecalcProgress(1, 5), progressList.first())

            job.cancel()
        }

    @Test
    fun `executeSync scheduling resync worker on REQUIRES_HISTORICAL_RESYNC result`() =
        runTest {
            coEvery { syncUseCase.sync(windowDays = 1, onProgress = any()) } returns
                app.readylytics.health.domain.model.Result.Failure(
                    reason = "Need full resync",
                    code = "REQUIRES_HISTORICAL_RESYNC",
                )

            controller.triggerDailySync()

            verify(exactly = 1) { workerScheduler.scheduleResyncWorker() }
        }

    @Test
    fun `background recalculation flows publish correctly`() =
        runTest {
            var isSyncing = false
            var progress: RecalcProgress? = null
            var completedCount = 0

            val job1 = launch { controller.isSyncing.collect { isSyncing = it } }
            val job2 = launch { controller.recalcProgress.collect { progress = it } }
            val job3 = launch { controller.syncCompletedEvent.collect { completedCount++ } }
            runCurrent()

            controller.onBackgroundRecalcStarted()
            runCurrent()
            kotlin.test.assertTrue(isSyncing)
            kotlin.test.assertNull(progress)

            controller.onBackgroundRecalcProgress(3, 10)
            runCurrent()
            kotlin.test.assertNotNull(progress)
            kotlin.test.assertEquals(3, progress?.current)
            kotlin.test.assertEquals(10, progress?.total)

            controller.onBackgroundRecalcFinished(success = true)
            runCurrent()
            kotlin.test.assertFalse(isSyncing)
            kotlin.test.assertNull(progress)
            kotlin.test.assertEquals(1, completedCount)

            job1.cancel()
            job2.cancel()
            job3.cancel()
        }

    @Test
    fun `evaluateAndSync triggers catchUpSync on first sync ALWAYS when installDate non-zero`() =
        runTest {
            val prefs =
                UserPreferences(
                    syncPreference = SyncPreference.ALWAYS,
                    lastSyncTimestamp = 0L,
                    installDate = 123456789L,
                )
            coEvery { settingsRepo.userPreferences } returns flowOf(prefs)
            coEvery { syncUseCase.catchUpSync(any()) } returns
                app.readylytics.health.domain.model.Result
                    .Success(Unit)

            controller.evaluateAndSync()

            coVerify(exactly = 1) { syncUseCase.catchUpSync(any()) }
            coVerify(exactly = 0) { syncUseCase.sync(any(), any()) }
        }

    @Test
    fun `evaluateAndSync triggers catchUpSync on first sync BY_TIME when installDate non-zero`() =
        runTest {
            val prefs =
                UserPreferences(
                    syncPreference = SyncPreference.BY_TIME,
                    lastSyncTimestamp = 0L,
                    installDate = 123456789L,
                )
            coEvery { settingsRepo.userPreferences } returns flowOf(prefs)
            coEvery { syncUseCase.catchUpSync(any()) } returns
                app.readylytics.health.domain.model.Result
                    .Success(Unit)

            controller.evaluateAndSync()

            coVerify(exactly = 1) { syncUseCase.catchUpSync(any()) }
            coVerify(exactly = 0) { syncUseCase.sync(any(), any()) }
        }
}
