package app.readylytics.health

import app.readylytics.health.data.preferences.BackupSchedule
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.domain.migration.DatabaseReadiness
import app.readylytics.health.domain.scoring.BackfillHistoricalBaselinesUseCase
import app.readylytics.health.domain.sync.HealthSyncUseCase
import app.readylytics.health.workers.WorkerScheduler
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DatabaseReadyStartupInitializerTest {
    private val healthSyncUseCase = mockk<HealthSyncUseCase>()
    private val backfill = mockk<BackfillHistoricalBaselinesUseCase>()
    private val healthSyncLazy = mockk<Lazy<HealthSyncUseCase>>()
    private val backfillLazy = mockk<Lazy<BackfillHistoricalBaselinesUseCase>>()
    private val settingsRepository = mockk<SettingsRepository>()
    private val workerScheduler = mockk<WorkerScheduler>(relaxed = true)

    @Test
    fun `migration-required startup does not resolve Room-backed lazies or schedule work`() =
        runTest {
            val initializer = createInitializer()

            initializer.initializeIfReady(DatabaseReadiness.MigrationRequired(fromVersion = 6))

            verify(exactly = 0) { healthSyncLazy.get() }
            verify(exactly = 0) { backfillLazy.get() }
            verify(exactly = 0) { workerScheduler.scheduleBackupWorker(any()) }
            verify(exactly = 0) { workerScheduler.scheduleBirthdayWorker() }
            verify(exactly = 0) { workerScheduler.scheduleDataCleanupWorker() }
            verify(exactly = 0) { workerScheduler.schedulePeriodicSync(any()) }
        }

    @Test
    fun `ready startup resolves Room-backed lazies and initializes exactly once`() =
        runTest {
            every { healthSyncLazy.get() } returns healthSyncUseCase
            every { backfillLazy.get() } returns backfill
            every { settingsRepository.backupSchedule } returns flowOf(BackupSchedule.DAILY)
            every { settingsRepository.backgroundSyncIntervalMinutes } returns flowOf(30)
            coEvery { healthSyncUseCase.withSyncLock<Int>(any()) } coAnswers {
                firstArg<suspend () -> Int>().invoke()
            }
            coEvery { backfill.execute() } returns 3
            val initializer = createInitializer()

            initializer.initializeIfReady(DatabaseReadiness.Ready)
            initializer.initializeIfReady(DatabaseReadiness.Ready)

            verify(exactly = 1) { healthSyncLazy.get() }
            verify(exactly = 1) { backfillLazy.get() }
            coVerify(exactly = 1) { backfill.execute() }
            verify(exactly = 1) { workerScheduler.scheduleBackupWorker(BackupSchedule.DAILY) }
            verify(exactly = 1) { workerScheduler.scheduleBirthdayWorker() }
            verify(exactly = 1) { workerScheduler.scheduleDataCleanupWorker() }
            verify(exactly = 1) { workerScheduler.schedulePeriodicSync(30L) }
        }

    private fun createInitializer() =
        DatabaseReadyStartupInitializer(
            healthSyncUseCase = healthSyncLazy,
            backfillHistoricalBaselines = backfillLazy,
            settingsRepository = settingsRepository,
            workerScheduler = workerScheduler,
        )
}
