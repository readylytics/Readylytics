package app.readylytics.health

import app.readylytics.health.data.preferences.BackupSchedule
import app.readylytics.health.data.preferences.SettingsDefaults
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.domain.migration.DatabaseReadiness
import app.readylytics.health.domain.scoring.BackfillHistoricalBaselinesUseCase
import app.readylytics.health.domain.sync.HealthSyncUseCase
import app.readylytics.health.workers.WorkerScheduler
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DatabaseReadyStartupInitializerScoringVersionTest {
    @Test
    fun `stale scoring version enqueues one recompute-only pass without bumping the stored version`() =
        runTest {
            val scheduler = FakeWorkerScheduler()
            val settings = mockk<SettingsRepository>(relaxed = true)
            val userPrefsFlow = MutableStateFlow(UserPreferences(scoringVersion = 0))
            coEvery { settings.userPreferences } returns userPrefsFlow.asStateFlow()
            coEvery { settings.backupSchedule } returns flowOf(BackupSchedule.DAILY)
            coEvery { settings.backgroundSyncEnabled } returns flowOf(false)

            val initializer =
                initializerWith(
                    storedScoringVersion = 0,
                    scheduler = scheduler,
                    settings = settings,
                )

            initializer.initializeIfReady(DatabaseReadiness.Ready)

            assertEquals(1, scheduler.recomputeOnlyRequests)
            coVerify(exactly = 0) { settings.updateScoringVersion(any()) }
        }

    @Test
    fun `current scoring version does not enqueue a recompute`() =
        runTest {
            val scheduler = FakeWorkerScheduler()
            val initializer =
                initializerWith(
                    storedScoringVersion = SettingsDefaults.CURRENT_SCORING_VERSION,
                    scheduler = scheduler,
                )

            initializer.initializeIfReady(DatabaseReadiness.Ready)

            assertEquals(0, scheduler.recomputeOnlyRequests)
        }

    private fun initializerWith(
        storedScoringVersion: Int,
        scheduler: FakeWorkerScheduler,
        settings: SettingsRepository = mockk(relaxed = true),
    ): DatabaseReadyStartupInitializer {
        val healthSyncUseCase = mockk<HealthSyncUseCase>()
        coEvery { healthSyncUseCase.withSyncLock<Int>(any()) } coAnswers {
            firstArg<suspend () -> Int>().invoke()
        }
        val backfill = mockk<BackfillHistoricalBaselinesUseCase>()
        coEvery { backfill.execute() } returns 0

        val healthSyncLazy = Lazy { healthSyncUseCase }
        val backfillLazy = Lazy { backfill }

        val userPrefsFlow = MutableStateFlow(UserPreferences(scoringVersion = storedScoringVersion))
        coEvery { settings.userPreferences } returns userPrefsFlow.asStateFlow()
        coEvery { settings.backupSchedule } returns flowOf(BackupSchedule.DAILY)
        coEvery { settings.backgroundSyncEnabled } returns flowOf(false)
        val settingsLazy = Lazy { settings }

        return DatabaseReadyStartupInitializer(
            healthSyncUseCase = healthSyncLazy,
            backfillHistoricalBaselines = backfillLazy,
            settingsRepository = settingsLazy,
            workerScheduler = scheduler,
        )
    }

    private class FakeWorkerScheduler : WorkerScheduler {
        var recomputeOnlyRequests = 0
            private set

        override fun scheduleDatabaseMigration() {}

        override fun scheduleResyncWorker(recomputeOnly: Boolean) {
            if (recomputeOnly) {
                recomputeOnlyRequests++
            }
        }

        override fun cancelResyncWorker() {}

        override fun scheduleBackupWorker(schedule: BackupSchedule) {}

        override fun scheduleBirthdayWorker() {}

        override fun schedulePeriodicSync(intervalMinutes: Long) {}

        override fun cancelPeriodicSync() {}

        override fun scheduleDataCleanupWorker() {}

        override fun scheduleDataRollupWorker() {}
    }
}
