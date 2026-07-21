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
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundSyncControllerTest {
    private val settingsRepo = mockk<SettingsRepository>()
    private val syncUseCase = mockk<HealthSyncUseCase>()
    private val workerScheduler = mockk<app.readylytics.health.workers.WorkerScheduler>(relaxed = true)

    // Fixed rather than Clock.systemDefaultZone() so every "today" computed below is deterministic
    // (DI-002): production resolves "today" via clock.withZone(zoneId), so this must be the same
    // clock instance the tests build their expected dates from.
    private val fixedClock = Clock.fixed(Instant.parse("2024-06-15T12:00:00Z"), ZoneId.of("UTC"))
    private val controller =
        ForegroundSyncController(settingsRepo, syncUseCase, dagger.Lazy { workerScheduler }, fixedClock)

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
                    .now(fixedClock.withZone(zone))
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
                    .now(fixedClock.withZone(zone))
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
    fun `evaluateAndSync caps the inline window and schedules the resync worker beyond it`() =
        runTest {
            // HC-007: a foreground, UI-blocking sync must never silently widen to an unbounded
            // window just because the app was closed for a long time.
            val zone = ZoneId.systemDefault()
            val ninetyDaysAgoMs =
                LocalDate
                    .now(fixedClock.withZone(zone))
                    .minusDays(90)
                    .atStartOfDay(zone)
                    .toInstant()
                    .toEpochMilli()
            val prefs =
                UserPreferences(
                    syncPreference = SyncPreference.ALWAYS,
                    lastSyncTimestamp = ninetyDaysAgoMs,
                )

            coEvery { settingsRepo.userPreferences } returns flowOf(prefs)
            coEvery { syncUseCase.sync(any(), any()) } returns
                app.readylytics.health.domain.model.Result
                    .Success(Unit)

            controller.evaluateAndSync()

            coVerify(exactly = 1) { syncUseCase.sync(windowDays = MAX_INLINE_RECOMPUTE_DAYS, onProgress = any()) }
            verify(exactly = 1) { workerScheduler.scheduleResyncWorker() }
        }

    @Test
    fun `evaluateAndSync resolves the catch-up window from the scoring zone, not the device zone`() =
        runTest {
            val originalZone = java.util.TimeZone.getDefault()
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
            try {
                // Kiritimati is UTC+14: three days ago in Kiritimati can already be a fourth
                // calendar day back in UTC, so an incorrect device-zone resolution would compute a
                // different windowDays than the correct scoring-zone resolution.
                val kiritimati = ZoneId.of("Pacific/Kiritimati")
                val lastSyncMs =
                    LocalDate
                        .now(fixedClock.withZone(kiritimati))
                        .minusDays(3)
                        .atStartOfDay(kiritimati)
                        .toInstant()
                        .toEpochMilli()
                val prefs =
                    UserPreferences(
                        syncPreference = SyncPreference.ALWAYS,
                        lastSyncTimestamp = lastSyncMs,
                        scoringZoneId = "Pacific/Kiritimati",
                    )
                coEvery { settingsRepo.userPreferences } returns flowOf(prefs)
                coEvery { syncUseCase.sync(any(), any()) } returns
                    app.readylytics.health.domain.model.Result
                        .Success(Unit)

                controller.evaluateAndSync()

                coVerify(exactly = 1) { syncUseCase.sync(windowDays = 4, onProgress = any()) }
            } finally {
                java.util.TimeZone.setDefault(originalZone)
            }
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
                val progress = firstArg<(ResyncPhase, Int, Int) -> Unit>()
                progress(ResyncPhase.RECOMPUTE, 1, 5)
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
            kotlin.test.assertEquals(RecalcProgress(ResyncPhase.RECOMPUTE, 1, 5), progressList.first())

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

            controller.onBackgroundRecalcProgress(ResyncPhase.RECOMPUTE, 3, 10)
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

    @Test
    fun `evaluateAndSync computes the catch-up window from the injected clock, not real wall-clock time`() =
        runTest {
            // DI-002: a controller wired to a clock fixed on a historical date must resolve
            // "today" from that clock, not the machine's real date. If computeWindowDays ever
            // reverted to LocalDate.now(zoneId) (the real system clock), the real gap between
            // 2019-01-10 and today would be thousands of days, capping windowDays at
            // MAX_INLINE_RECOMPUTE_DAYS instead of the small, exact value asserted below.
            val historicalClock = Clock.fixed(Instant.parse("2019-01-10T00:00:00Z"), ZoneId.of("UTC"))
            val historicalController =
                ForegroundSyncController(settingsRepo, syncUseCase, dagger.Lazy { workerScheduler }, historicalClock)
            val zone = ZoneId.of("UTC")
            val lastSyncMs =
                LocalDate
                    .of(2019, 1, 10)
                    .minusDays(3)
                    .atStartOfDay(zone)
                    .toInstant()
                    .toEpochMilli()
            val prefs =
                UserPreferences(
                    syncPreference = SyncPreference.ALWAYS,
                    lastSyncTimestamp = lastSyncMs,
                    scoringZoneId = "UTC",
                )
            coEvery { settingsRepo.userPreferences } returns flowOf(prefs)
            coEvery { syncUseCase.sync(any(), any()) } returns
                app.readylytics.health.domain.model.Result
                    .Success(Unit)

            historicalController.evaluateAndSync()

            coVerify(exactly = 1) { syncUseCase.sync(windowDays = 4, onProgress = any()) }
        }
}
