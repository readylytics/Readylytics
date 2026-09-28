package app.readylytics.health

import app.readylytics.health.core.model.data.preferences.BackupSchedule
import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.migration.DatabaseReadiness
import app.readylytics.health.core.model.domain.repository.WorkoutTrimpBackfillStatus
import app.readylytics.health.core.model.domain.sync.DirtyRangeStore
import app.readylytics.health.core.model.domain.sync.DirtyTicket
import app.readylytics.health.core.model.domain.sync.HealthMutationCoordinator
import app.readylytics.health.core.model.domain.sync.RETIRED_AGING_DIRTY_REASONS
import app.readylytics.health.core.model.domain.sync.RecalcTrigger
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
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
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
    fun versionFourNeedsRecommendationBackfill() =
        runTest {
            // Task 5: version 4 predates workout-recommendation assembly (v5). Existing users
            // stored at v4 must get one recompute-only pass to backfill it.
            val scheduler = FakeWorkerScheduler()
            val initializer = initializerWith(storedScoringVersion = 4, scheduler = scheduler)

            initializer.initializeIfReady(DatabaseReadiness.Ready)

            assertEquals(1, scheduler.recomputeOnlyRequests)
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
    fun `migration failure still enqueues pending dirty recompute`() =
        runTest {
            val scheduler = FakeWorkerScheduler()
            val physiology = mockk<PhysiologyPreferences>(relaxed = true)
            coEvery { physiology.migrateTrimpDefaultsIfNeeded() } throws
                IOException("datastore unavailable")
            val initializer =
                initializerWith(
                    storedScoringVersion = SettingsDefaults.CURRENT_SCORING_VERSION,
                    scheduler = scheduler,
                    physiology = physiology,
                    dirtyRangeStore = pendingDirtyStore(),
                )

            val result = initializer.initializeIfReady(DatabaseReadiness.Ready)

            assertEquals(StartupInitializationResult.COMPLETE, result)
            assertEquals(1, scheduler.recomputeOnlyRequests)
            assertEquals(RecalcTrigger.STARTUP_PENDING_DIRTY, scheduler.lastTrigger)
        }

    @Test
    fun `migration failure with all recompute gates clear enqueues nothing`() =
        runTest {
            val scheduler = FakeWorkerScheduler()
            val physiology = mockk<PhysiologyPreferences>(relaxed = true)
            coEvery { physiology.migrateTrimpDefaultsIfNeeded() } throws IOException("datastore unavailable")
            val initializer =
                initializerWith(
                    storedScoringVersion = SettingsDefaults.CURRENT_SCORING_VERSION,
                    scheduler = scheduler,
                    physiology = physiology,
                )

            assertEquals(StartupInitializationResult.COMPLETE, initializer.initializeIfReady(DatabaseReadiness.Ready))
            assertEquals(0, scheduler.recomputeOnlyRequests)
        }

    @Test
    fun `three startup gates select scoring version trigger once after migration failure`() =
        runTest {
            val scheduler = FakeWorkerScheduler()
            val physiology = mockk<PhysiologyPreferences>(relaxed = true)
            coEvery { physiology.migrateTrimpDefaultsIfNeeded() } throws IOException("datastore unavailable")
            val initializer =
                initializerWith(
                    storedScoringVersion = 0,
                    scheduler = scheduler,
                    physiology = physiology,
                    backfillStatus = FakeBackfillStatus(hasUnbackfilled = true),
                    dirtyRangeStore = pendingDirtyStore(),
                )

            assertEquals(StartupInitializationResult.COMPLETE, initializer.initializeIfReady(DatabaseReadiness.Ready))
            assertEquals(1, scheduler.recomputeOnlyRequests)
            assertEquals(RecalcTrigger.STARTUP_SCORING_VERSION, scheduler.lastTrigger)
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
                val fixedInstant = Instant.parse("2026-08-31T12:30:00Z")

                initializerWith(
                    storedScoringVersion = prefs.scoringVersion,
                    scheduler = scheduler,
                    backfillStatus = backfillStatus,
                    userPreferences = prefs,
                    clock = Clock.fixed(fixedInstant, ZoneOffset.UTC),
                ).initializeIfReady(DatabaseReadiness.Ready)

                val expectedBoundary =
                    RetentionBounds
                        .resolveResyncStartDate(prefs, fixedInstant.atZone(scoringZone).toLocalDate())
                        .atStartOfDay(scoringZone)
                        .toInstant()
                        .toEpochMilli()
                assertTrue(
                    "Startup boundary ${backfillStatus.retentionStartMs} must match $expectedBoundary, " +
                        "not system-zone midnight in $systemZone",
                    backfillStatus.retentionStartMs == expectedBoundary,
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

    @Test
    fun `pending dirty ranges trigger recompute worker scheduling even on current scoring version`() =
        runTest {
            val scheduler = FakeWorkerScheduler()
            val dirtyStore =
                object : DirtyRangeStore {
                    override suspend fun pending(limit: Int): List<DirtyTicket> =
                        listOf(
                            DirtyTicket(
                                id = 1L,
                                sourceGeneration = 1L,
                                nextDay = java.time.LocalDate.of(2026, 9, 1),
                                endInclusive = java.time.LocalDate.of(2026, 9, 5),
                                scoringSnapshotId = "s1",
                            ),
                        )
                }
            val initializer =
                initializerWith(
                    storedScoringVersion = SettingsDefaults.CURRENT_SCORING_VERSION,
                    scheduler = scheduler,
                    backfillStatus = FakeBackfillStatus(hasUnbackfilled = false),
                    dirtyRangeStore = dirtyStore,
                )

            initializer.initializeIfReady(DatabaseReadiness.Ready)

            assertEquals(1, scheduler.recomputeOnlyRequests)
            assertEquals(
                app.readylytics.health.core.model.domain.sync.RecalcTrigger.STARTUP_PENDING_DIRTY,
                scheduler.lastTrigger,
            )
        }

    @Test
    fun `retired aging tickets are discarded and do not trigger a recompute`() =
        runTest {
            val scheduler = FakeWorkerScheduler()
            val dirtyStore =
                object : DirtyRangeStore {
                    var tickets =
                        listOf(
                            DirtyTicket(
                                id = 1L,
                                sourceGeneration = 1L,
                                nextDay = java.time.LocalDate.of(2026, 7, 1),
                                endInclusive = java.time.LocalDate.of(2026, 8, 31),
                                scoringSnapshotId = "ACTIVE",
                                reason = "HOT_TIER_ROLLUP",
                            ),
                        )

                    override suspend fun discardRetiredAgingTickets(): Int {
                        val before = tickets.size
                        tickets = tickets.filterNot { it.reason in RETIRED_AGING_DIRTY_REASONS }
                        return before - tickets.size
                    }

                    override suspend fun pending(limit: Int): List<DirtyTicket> = tickets
                }
            val initializer =
                initializerWith(
                    storedScoringVersion = SettingsDefaults.CURRENT_SCORING_VERSION,
                    scheduler = scheduler,
                    backfillStatus = FakeBackfillStatus(hasUnbackfilled = false),
                    dirtyRangeStore = dirtyStore,
                )

            initializer.initializeIfReady(DatabaseReadiness.Ready)

            assertEquals(0, scheduler.recomputeOnlyRequests)
            assertEquals(emptyList<DirtyTicket>(), dirtyStore.tickets)
        }

    @Test
    fun `failing dirty range query does not crash startup or block initialization`() =
        runTest {
            val scheduler = FakeWorkerScheduler()
            val failingDirtyStore =
                object : DirtyRangeStore {
                    override suspend fun pending(limit: Int): List<DirtyTicket> = throw IOException("database locked")
                }
            val initializer =
                initializerWith(
                    storedScoringVersion = SettingsDefaults.CURRENT_SCORING_VERSION,
                    scheduler = scheduler,
                    backfillStatus = FakeBackfillStatus(hasUnbackfilled = false),
                    dirtyRangeStore = failingDirtyStore,
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
        dirtyRangeStore: DirtyRangeStore? = null,
        clock: Clock = Clock.fixed(Instant.parse("2026-08-31T12:00:00Z"), ZoneOffset.UTC),
    ): DatabaseReadyStartupInitializer {
        val healthMutationCoordinator =
            object : HealthMutationCoordinator {
                override suspend fun <T> withMutation(block: suspend () -> T): T = block()

                override suspend fun <T> withMaintenance(
                    operationId: String,
                    block: suspend () -> T,
                ): T = block()
            }
        val backfill = mockk<BackfillHistoricalBaselinesUseCase>()
        coEvery { backfill.execute() } returns 0

        val backfillLazy = Lazy { backfill }
        val physiologyLazy = Lazy { physiology }

        val userPrefsFlow = MutableStateFlow(userPreferences)
        coEvery { settings.userPreferences } returns userPrefsFlow.asStateFlow()
        coEvery { settings.backupSchedule } returns flowOf(BackupSchedule.DAILY)
        coEvery { settings.backgroundSyncEnabled } returns flowOf(false)
        val settingsLazy = Lazy { settings }
        val dirtyRangeLazy =
            Lazy {
                dirtyRangeStore ?: object : DirtyRangeStore {
                    override suspend fun pending(limit: Int): List<DirtyTicket> = emptyList()
                }
            }

        return DatabaseReadyStartupInitializer(
            backfillHistoricalBaselines = backfillLazy,
            settingsRepository = settingsLazy,
            physiologyPreferences = physiologyLazy,
            workerScheduler = scheduler,
            workoutTrimpBackfillStatus = Lazy { backfillStatus },
            dirtyRangeStore = dirtyRangeLazy,
            clock = clock,
            healthMutationCoordinator = Lazy { healthMutationCoordinator },
        )
    }

    private class FakeBackfillStatus(
        private val hasUnbackfilled: Boolean,
    ) : WorkoutTrimpBackfillStatus {
        override suspend fun hasUnbackfilledWorkouts(retentionStartMs: Long): Boolean = hasUnbackfilled
    }

    private fun pendingDirtyStore(): DirtyRangeStore =
        object : DirtyRangeStore {
            override suspend fun pending(limit: Int): List<DirtyTicket> =
                listOf(
                    DirtyTicket(
                        id = 1L,
                        sourceGeneration = 1L,
                        nextDay = LocalDate.of(2026, 9, 1),
                        endInclusive = LocalDate.of(2026, 9, 5),
                        scoringSnapshotId = "s1",
                    ),
                )
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
        var lastTrigger: app.readylytics.health.core.model.domain.sync.RecalcTrigger? = null
            private set

        override fun scheduleDatabaseMigration() { /* no-op */ }

        override suspend fun scheduleResyncWorker(
            recomputeOnly: Boolean,
            startDate: java.time.LocalDate?,
            endDate: java.time.LocalDate?,
            trigger: app.readylytics.health.core.model.domain.sync.RecalcTrigger,
            triggerDetail: String?,
        ) {
            if (recomputeOnly) {
                recomputeOnlyRequests++
                lastTrigger = trigger
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
