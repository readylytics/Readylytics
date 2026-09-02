package app.readylytics.health

import app.readylytics.health.core.healthconnect.domain.sync.HealthSyncUseCase
import app.readylytics.health.core.model.data.preferences.BackupSchedule
import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.migration.DatabaseReadiness
import app.readylytics.health.core.model.domain.repository.WorkoutTrimpBackfillStatus
import app.readylytics.health.core.model.domain.util.RetentionBounds
import app.readylytics.health.core.model.workers.WorkerScheduler
import app.readylytics.health.core.scoring.domain.scoring.BackfillHistoricalBaselinesUseCase
import app.readylytics.health.data.preferences.PhysiologyPreferences
import app.readylytics.health.data.preferences.SettingsRepository
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone

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

    @Test
    fun `ready startup runs trimp normalization migration before recompute check`() =
        runTest {
            val scheduler = FakeWorkerScheduler()
            val physiology = mockk<PhysiologyPreferences>(relaxed = true)
            val initializer =
                initializerWith(
                    storedScoringVersion = 0,
                    scheduler = scheduler,
                    physiology = physiology,
                )

            initializer.initializeIfReady(DatabaseReadiness.Ready)

            coVerify(exactly = 1) { physiology.migrateTrimpDefaultsIfNeeded() }
            assertEquals(1, scheduler.recomputeOnlyRequests)
        }

    @Test
    fun `migration failure suppresses recompute scheduling so a later launch re-enqueues`() =
        runTest {
            val scheduler = FakeWorkerScheduler()
            val physiology = mockk<PhysiologyPreferences>(relaxed = true)
            coEvery { physiology.migrateTrimpDefaultsIfNeeded() } throws
                IOException("datastore unavailable")
            val initializer =
                initializerWith(
                    storedScoringVersion = 0,
                    scheduler = scheduler,
                    physiology = physiology,
                )

            val result = initializer.initializeIfReady(DatabaseReadiness.Ready)

            assertEquals(StartupInitializationResult.COMPLETE, result)
            assertEquals(0, scheduler.recomputeOnlyRequests)
        }

    @Test
    fun `unbackfilled canonical trimp enqueues a recompute even on the current scoring version`() =
        runTest {
            val scheduler = FakeWorkerScheduler()
            val initializer =
                initializerWith(
                    storedScoringVersion = SettingsDefaults.CURRENT_SCORING_VERSION,
                    scheduler = scheduler,
                    backfillStatus = FakeBackfillStatus(hasUnbackfilled = true),
                )

            initializer.initializeIfReady(DatabaseReadiness.Ready)

            assertEquals(1, scheduler.recomputeOnlyRequests)
        }

    @Test
    fun `startup backfill gate uses scoring-zone retention boundary when system zone differs`() =
        runTest {
            val originalTimeZone = TimeZone.getDefault()
            val systemZone = ZoneId.of("Etc/GMT+12")
            val scoringZone = ZoneId.of("Pacific/Kiritimati")
            TimeZone.setDefault(TimeZone.getTimeZone(systemZone))
            try {
                val prefs =
                    UserPreferences(
                        scoringVersion = SettingsDefaults.CURRENT_SCORING_VERSION,
                        scoringZoneId = scoringZone.id,
                        retentionDaysEnabled = true,
                        retentionDays = 30,
                    )
                val backfillStatus = CapturingBackfillStatus()
                val scheduler = FakeWorkerScheduler()
                val before = Instant.now()

                initializerWith(
                    storedScoringVersion = prefs.scoringVersion,
                    scheduler = scheduler,
                    backfillStatus = backfillStatus,
                    userPreferences = prefs,
                ).initializeIfReady(DatabaseReadiness.Ready)

                val after = Instant.now()
                val validScoringBoundaries =
                    listOf(before, after).mapTo(mutableSetOf()) { instant ->
                        RetentionBounds
                            .resolveResyncStartDate(prefs, instant.atZone(scoringZone).toLocalDate())
                            .atStartOfDay(scoringZone)
                            .toInstant()
                            .toEpochMilli()
                    }
                assertTrue(
                    "Startup boundary ${backfillStatus.retentionStartMs} must match $validScoringBoundaries, " +
                        "not system-zone midnight in $systemZone",
                    backfillStatus.retentionStartMs in validScoringBoundaries,
                )
                assertEquals(1, scheduler.recomputeOnlyRequests)
            } finally {
                TimeZone.setDefault(originalTimeZone)
            }
        }

    @Test
    fun `fully backfilled history on the current scoring version enqueues nothing`() =
        runTest {
            val scheduler = FakeWorkerScheduler()
            val initializer =
                initializerWith(
                    storedScoringVersion = SettingsDefaults.CURRENT_SCORING_VERSION,
                    scheduler = scheduler,
                    backfillStatus = FakeBackfillStatus(hasUnbackfilled = false),
                )

            initializer.initializeIfReady(DatabaseReadiness.Ready)

            assertEquals(0, scheduler.recomputeOnlyRequests)
        }

    @Test
    fun `stale version and unbackfilled rows together enqueue exactly one recompute`() =
        runTest {
            val scheduler = FakeWorkerScheduler()
            val initializer =
                initializerWith(
                    storedScoringVersion = 0,
                    scheduler = scheduler,
                    backfillStatus = FakeBackfillStatus(hasUnbackfilled = true),
                )

            initializer.initializeIfReady(DatabaseReadiness.Ready)

            assertEquals(1, scheduler.recomputeOnlyRequests)
        }

    @Test
    fun `a failing backfill status query never blocks startup`() =
        runTest {
            val scheduler = FakeWorkerScheduler()
            val failing =
                object : WorkoutTrimpBackfillStatus {
                    override suspend fun hasUnbackfilledWorkouts(retentionStartMs: Long): Boolean =
                        throw IOException("database unavailable")
                }
            val initializer =
                initializerWith(
                    storedScoringVersion = SettingsDefaults.CURRENT_SCORING_VERSION,
                    scheduler = scheduler,
                    backfillStatus = failing,
                )

            val result = initializer.initializeIfReady(DatabaseReadiness.Ready)

            assertEquals(StartupInitializationResult.COMPLETE, result)
            assertEquals(0, scheduler.recomputeOnlyRequests)
        }

    private fun initializerWith(
        storedScoringVersion: Int,
        scheduler: FakeWorkerScheduler,
        settings: SettingsRepository = mockk(relaxed = true),
        physiology: PhysiologyPreferences = mockk(relaxed = true),
        backfillStatus: WorkoutTrimpBackfillStatus = FakeBackfillStatus(hasUnbackfilled = false),
        userPreferences: UserPreferences = UserPreferences(scoringVersion = storedScoringVersion),
    ): DatabaseReadyStartupInitializer {
        val healthSyncUseCase = mockk<HealthSyncUseCase>()
        coEvery { healthSyncUseCase.withSyncLock<Int>(any()) } coAnswers {
            firstArg<suspend () -> Int>().invoke()
        }
        val backfill = mockk<BackfillHistoricalBaselinesUseCase>()
        coEvery { backfill.execute() } returns 0

        val healthSyncLazy = Lazy { healthSyncUseCase }
        val backfillLazy = Lazy { backfill }
        val physiologyLazy = Lazy { physiology }

        val userPrefsFlow = MutableStateFlow(userPreferences)
        coEvery { settings.userPreferences } returns userPrefsFlow.asStateFlow()
        coEvery { settings.backupSchedule } returns flowOf(BackupSchedule.DAILY)
        coEvery { settings.backgroundSyncEnabled } returns flowOf(false)
        val settingsLazy = Lazy { settings }

        return DatabaseReadyStartupInitializer(
            healthSyncUseCase = healthSyncLazy,
            backfillHistoricalBaselines = backfillLazy,
            settingsRepository = settingsLazy,
            physiologyPreferences = physiologyLazy,
            workerScheduler = scheduler,
            workoutTrimpBackfillStatus = Lazy { backfillStatus },
        )
    }

    private class FakeBackfillStatus(
        private val hasUnbackfilled: Boolean,
    ) : WorkoutTrimpBackfillStatus {
        override suspend fun hasUnbackfilledWorkouts(retentionStartMs: Long): Boolean = hasUnbackfilled
    }

    private class CapturingBackfillStatus : WorkoutTrimpBackfillStatus {
        var retentionStartMs: Long? = null
            private set

        override suspend fun hasUnbackfilledWorkouts(retentionStartMs: Long): Boolean {
            this.retentionStartMs = retentionStartMs
            return true
        }
    }

    private class FakeWorkerScheduler : WorkerScheduler {
        var recomputeOnlyRequests = 0
            private set

        override fun scheduleDatabaseMigration() { /* no-op */ }

        override fun scheduleResyncWorker(
            recomputeOnly: Boolean,
            startDate: java.time.LocalDate?,
            endDate: java.time.LocalDate?,
        ) {
            if (recomputeOnly) {
                recomputeOnlyRequests++
            }
        }

        override fun cancelResyncWorker() { /* no-op */ }

        override fun scheduleTrainingReadinessRecompute(
            config: app.readylytics.health.core.model.domain.scoring.TrainingReadinessConfig,
        ) { /* no-op */ }

        override fun scheduleBackupWorker(schedule: BackupSchedule) { /* no-op */ }

        override fun scheduleBirthdayWorker() { /* no-op */ }

        override fun schedulePeriodicSync(intervalMinutes: Long) { /* no-op */ }

        override fun cancelPeriodicSync() { /* no-op */ }

        override fun scheduleDataCleanupWorker() { /* no-op */ }

        override fun scheduleDataRollupWorker() { /* no-op */ }
    }
}
