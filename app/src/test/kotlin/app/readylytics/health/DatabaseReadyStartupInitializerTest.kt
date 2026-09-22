package app.readylytics.health

import android.content.Context
import app.readylytics.health.core.healthconnect.domain.sync.HealthSyncUseCase
import app.readylytics.health.core.model.data.preferences.BackupSchedule
import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.migration.DatabaseReadiness
import app.readylytics.health.core.model.domain.repository.WorkoutTrimpBackfillStatus
import app.readylytics.health.core.model.workers.WorkerScheduler
import app.readylytics.health.core.scoring.domain.scoring.BackfillHistoricalBaselinesUseCase
import app.readylytics.health.data.backup.RestoreMaintenanceCoordinator
import app.readylytics.health.data.preferences.PhysiologyPreferences
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.domain.migration.DatabaseMigrationUiState
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class DatabaseReadyStartupInitializerTest {
    private val healthSyncUseCase = mockk<HealthSyncUseCase>()
    private val backfill = mockk<BackfillHistoricalBaselinesUseCase>()
    private val healthSyncLazy = mockk<Lazy<HealthSyncUseCase>>()
    private val backfillLazy = mockk<Lazy<BackfillHistoricalBaselinesUseCase>>()
    private val settingsRepository = mockk<SettingsRepository>()
    private val settingsRepositoryLazy = mockk<Lazy<SettingsRepository>>()
    private val physiologyPreferences = mockk<PhysiologyPreferences>(relaxed = true)
    private val physiologyPreferencesLazy = mockk<Lazy<PhysiologyPreferences>>()
    private val workerScheduler = mockk<WorkerScheduler>(relaxed = true)

    @Test
    fun `migration-required startup does not resolve Room-backed lazies or schedule work`() =
        runTest {
            val initializer = createInitializer()

            initializer.initializeIfReady(DatabaseReadiness.MigrationRequired(fromVersion = 6))

            verify(exactly = 0) { healthSyncLazy.get() }
            verify(exactly = 0) { backfillLazy.get() }
            verify(exactly = 0) { settingsRepositoryLazy.get() }
            verify(exactly = 0) { physiologyPreferencesLazy.get() }
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
            every { settingsRepository.backgroundSyncEnabled } returns flowOf(true)
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
            verify(exactly = 1) { settingsRepositoryLazy.get() }
            coVerify(exactly = 1) { backfill.execute() }
            verify(exactly = 1) { workerScheduler.scheduleBackupWorker(BackupSchedule.DAILY) }
            verify(exactly = 1) { workerScheduler.scheduleBirthdayWorker() }
            verify(exactly = 1) { workerScheduler.scheduleDataCleanupWorker() }
            verify(exactly = 1) { workerScheduler.schedulePeriodicSync(30L) }
            verify(exactly = 0) { workerScheduler.cancelPeriodicSync() }
        }

    @Test
    fun `ready startup cancels periodic sync when background sync is disabled`() =
        runTest {
            every { healthSyncLazy.get() } returns healthSyncUseCase
            every { backfillLazy.get() } returns backfill
            every { settingsRepository.backupSchedule } returns flowOf(BackupSchedule.WEEKLY)
            every { settingsRepository.backgroundSyncEnabled } returns flowOf(false)
            coEvery { healthSyncUseCase.withSyncLock<Int>(any()) } coAnswers {
                firstArg<suspend () -> Int>().invoke()
            }
            coEvery { backfill.execute() } returns 0
            val initializer = createInitializer()

            initializer.initializeIfReady(DatabaseReadiness.Ready)

            verify(exactly = 1) { workerScheduler.scheduleBackupWorker(BackupSchedule.WEEKLY) }
            verify(exactly = 1) { workerScheduler.scheduleBirthdayWorker() }
            verify(exactly = 1) { workerScheduler.scheduleDataCleanupWorker() }
            verify(exactly = 1) { workerScheduler.cancelPeriodicSync() }
            verify(exactly = 0) { workerScheduler.schedulePeriodicSync(any()) }
        }

    @Test
    fun `cancellation while reading settings resets guard so a later Ready retries`() =
        runTest {
            var enabledReads = 0
            every { healthSyncLazy.get() } returns healthSyncUseCase
            every { backfillLazy.get() } returns backfill
            every { settingsRepository.backupSchedule } returns flowOf(BackupSchedule.DAILY)
            every {
                settingsRepository.backgroundSyncEnabled
            } returns
                flow {
                    enabledReads += 1
                    if (enabledReads == 1) throw CancellationException("settings read cancelled")
                    emit(true)
                }
            every { settingsRepository.backgroundSyncIntervalMinutes } returns flowOf(30)
            coEvery { healthSyncUseCase.withSyncLock<Int>(any()) } coAnswers {
                firstArg<suspend () -> Int>().invoke()
            }
            coEvery { backfill.execute() } returns 0
            val initializer = createInitializer()

            var cancellationRethrown = false
            try {
                initializer.initializeIfReady(DatabaseReadiness.Ready)
            } catch (_: CancellationException) {
                cancellationRethrown = true
            }
            assertTrue(cancellationRethrown)
            initializer.initializeIfReady(DatabaseReadiness.Ready)

            verify(exactly = 2) { healthSyncLazy.get() }
            verify(exactly = 2) { backfillLazy.get() }
            coVerify(exactly = 2) { backfill.execute() }
            verify(exactly = 1) { workerScheduler.scheduleBackupWorker(BackupSchedule.DAILY) }
            verify(exactly = 1) { workerScheduler.scheduleBirthdayWorker() }
            verify(exactly = 1) { workerScheduler.scheduleDataCleanupWorker() }
            verify(exactly = 1) { workerScheduler.schedulePeriodicSync(30L) }
        }

    @Test
    fun `stable Ready state retries an ordinary settings failure and completes startup`() =
        runTest {
            var backupScheduleReads = 0
            every { healthSyncLazy.get() } returns healthSyncUseCase
            every { backfillLazy.get() } returns backfill
            every {
                settingsRepository.backupSchedule
            } returns
                flow {
                    backupScheduleReads += 1
                    if (backupScheduleReads == 1) error("temporary settings failure")
                    emit(BackupSchedule.DAILY)
                }
            every { settingsRepository.backgroundSyncEnabled } returns flowOf(true)
            every { settingsRepository.backgroundSyncIntervalMinutes } returns flowOf(30)
            coEvery { healthSyncUseCase.withSyncLock<Int>(any()) } coAnswers {
                firstArg<suspend () -> Int>().invoke()
            }
            coEvery { backfill.execute() } returns 0
            val readiness = MutableStateFlow(DatabaseMigrationUiState(DatabaseReadiness.Ready))
            val coordinator =
                DatabaseReadyStartupCoordinator(
                    initializer = createInitializer(),
                    retryDelaysMillis = listOf(1L),
                    waitBeforeRetry = {},
                )

            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                coordinator.observe(readiness)
            }
            advanceUntilIdle()

            verify(exactly = 2) { healthSyncLazy.get() }
            verify(exactly = 1) { workerScheduler.scheduleBackupWorker(BackupSchedule.DAILY) }
            verify(exactly = 1) { workerScheduler.scheduleBirthdayWorker() }
            verify(exactly = 1) { workerScheduler.scheduleDataCleanupWorker() }
            verify(exactly = 1) { workerScheduler.schedulePeriodicSync(30L) }
        }

    @Test
    fun `retry stops when readiness becomes non-Ready`() =
        runTest {
            every { healthSyncLazy.get() } returns healthSyncUseCase
            every { backfillLazy.get() } returns backfill
            every {
                settingsRepository.backupSchedule
            } returns flow { error("persistent settings failure") }
            coEvery { healthSyncUseCase.withSyncLock<Int>(any()) } coAnswers {
                firstArg<suspend () -> Int>().invoke()
            }
            coEvery { backfill.execute() } returns 0
            val readiness = MutableStateFlow(DatabaseMigrationUiState(DatabaseReadiness.Ready))
            val coordinator =
                DatabaseReadyStartupCoordinator(
                    initializer = createInitializer(),
                    retryDelaysMillis = listOf(1L, 2L),
                    waitBeforeRetry = {
                        readiness.value =
                            DatabaseMigrationUiState(
                                DatabaseReadiness.MigrationRequired(fromVersion = 6),
                            )
                    },
                )

            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                coordinator.observe(readiness)
            }
            advanceUntilIdle()

            verify(exactly = 1) { healthSyncLazy.get() }
            verify(exactly = 0) { workerScheduler.scheduleBackupWorker(any()) }
            verify(exactly = 0) { workerScheduler.schedulePeriodicSync(any()) }
        }

    @Test
    fun `cancelling readiness observation stops a pending retry`() =
        runTest {
            every { healthSyncLazy.get() } returns healthSyncUseCase
            every { backfillLazy.get() } returns backfill
            every {
                settingsRepository.backupSchedule
            } returns flow { error("persistent settings failure") }
            coEvery { healthSyncUseCase.withSyncLock<Int>(any()) } coAnswers {
                firstArg<suspend () -> Int>().invoke()
            }
            coEvery { backfill.execute() } returns 0
            val retryWaitStarted = CompletableDeferred<Unit>()
            val readiness = MutableStateFlow(DatabaseMigrationUiState(DatabaseReadiness.Ready))
            val coordinator =
                DatabaseReadyStartupCoordinator(
                    initializer = createInitializer(),
                    retryDelaysMillis = listOf(1L),
                    waitBeforeRetry = {
                        retryWaitStarted.complete(Unit)
                        awaitCancellation()
                    },
                )
            val observation =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    coordinator.observe(readiness)
                }

            retryWaitStarted.await()
            observation.cancelAndJoin()

            assertTrue(observation.isCancelled)
            verify(exactly = 1) { healthSyncLazy.get() }
            verify(exactly = 0) { workerScheduler.scheduleBackupWorker(any()) }
            verify(exactly = 0) { workerScheduler.schedulePeriodicSync(any()) }
        }

    @Test
    fun `ready startup cleans up orphan backup staging before scheduling work`() =
        runTest {
            val cacheDir =
                java.nio.file.Files
                    .createTempDirectory("cache")
                    .toFile()
            try {
                val stagingDir = File(cacheDir, "backup-staging").apply { mkdirs() }
                val orphan = File(stagingDir, "backup_orphan.json").apply { writeText("{}") }
                val context = mockk<Context>()
                every { context.cacheDir } returns cacheDir

                every { healthSyncLazy.get() } returns healthSyncUseCase
                every { backfillLazy.get() } returns backfill
                every { settingsRepository.backupSchedule } returns flowOf(BackupSchedule.DAILY)
                every { settingsRepository.backgroundSyncEnabled } returns flowOf(false)
                coEvery { healthSyncUseCase.withSyncLock<Int>(any()) } coAnswers {
                    firstArg<suspend () -> Int>().invoke()
                }
                coEvery { backfill.execute() } returns 0
                val initializer = createInitializer(context = context)

                initializer.initializeIfReady(DatabaseReadiness.Ready)

                assertFalse(orphan.exists())
                verify(exactly = 1) { workerScheduler.scheduleBackupWorker(BackupSchedule.DAILY) }
            } finally {
                cacheDir.deleteRecursively()
            }
        }

    @Test
    fun `ready startup recovers interrupted restore before backfill`() =
        runTest {
            val restoreCoordinator = mockk<RestoreMaintenanceCoordinator>()
            coEvery { restoreCoordinator.recoverInterruptedRestoreOnStartup() } returns true
            coEvery { restoreCoordinator.isMaintenancePending() } returns false

            every { healthSyncLazy.get() } returns healthSyncUseCase
            every { backfillLazy.get() } returns backfill
            every { settingsRepository.backupSchedule } returns flowOf(BackupSchedule.DAILY)
            every { settingsRepository.backgroundSyncEnabled } returns flowOf(false)
            coEvery { healthSyncUseCase.withSyncLock<Int>(any()) } coAnswers {
                firstArg<suspend () -> Int>().invoke()
            }
            coEvery { backfill.execute() } returns 0

            val initializer =
                createInitializer(
                    restoreMaintenanceCoordinator = Lazy { restoreCoordinator },
                )

            val result = initializer.initializeIfReady(DatabaseReadiness.Ready)
            assertEquals(StartupInitializationResult.COMPLETE, result)

            coVerify(exactly = 1) { restoreCoordinator.recoverInterruptedRestoreOnStartup() }
            coVerify(exactly = 1) { backfill.execute() }
        }

    @Test
    fun `failed restore recovery keeps startup retryable until maintenance is released`() =
        runTest {
            val restoreCoordinator = mockk<RestoreMaintenanceCoordinator>()
            coEvery { restoreCoordinator.recoverInterruptedRestoreOnStartup() } returns false
            coEvery { restoreCoordinator.isMaintenancePending() } returns true
            every { healthSyncLazy.get() } returns healthSyncUseCase
            every { backfillLazy.get() } returns backfill
            every { settingsRepository.backupSchedule } returns flowOf(BackupSchedule.DAILY)
            every { settingsRepository.backgroundSyncEnabled } returns flowOf(false)
            coEvery { healthSyncUseCase.withSyncLock<Int>(any()) } coAnswers {
                firstArg<suspend () -> Int>().invoke()
            }
            coEvery { backfill.execute() } returns 0
            val initializer = createInitializer(restoreMaintenanceCoordinator = Lazy { restoreCoordinator })

            assertEquals(
                StartupInitializationResult.RETRYABLE_FAILURE,
                initializer.initializeIfReady(DatabaseReadiness.Ready),
            )
            coVerify(exactly = 0) { backfill.execute() }
            verify(exactly = 0) { workerScheduler.scheduleBackupWorker(any()) }

            coEvery { restoreCoordinator.recoverInterruptedRestoreOnStartup() } returns true
            coEvery { restoreCoordinator.isMaintenancePending() } returns false
            assertEquals(StartupInitializationResult.COMPLETE, initializer.initializeIfReady(DatabaseReadiness.Ready))
            coVerify(exactly = 1) { backfill.execute() }
        }

    private fun createInitializer(
        context: Context? = null,
        restoreMaintenanceCoordinator: Lazy<RestoreMaintenanceCoordinator>? = null,
    ): DatabaseReadyStartupInitializer {
        every { settingsRepositoryLazy.get() } returns settingsRepository
        every { physiologyPreferencesLazy.get() } returns physiologyPreferences
        every { settingsRepository.userPreferences } returns
            flowOf(
                UserPreferences(
                    scoringVersion = SettingsDefaults.CURRENT_SCORING_VERSION,
                ),
            )
        return DatabaseReadyStartupInitializer(
            healthSyncUseCase = healthSyncLazy,
            backfillHistoricalBaselines = backfillLazy,
            settingsRepository = settingsRepositoryLazy,
            physiologyPreferences = physiologyPreferencesLazy,
            workerScheduler = workerScheduler,
            workoutTrimpBackfillStatus =
                Lazy {
                    object : WorkoutTrimpBackfillStatus {
                        override suspend fun hasUnbackfilledWorkouts(retentionStartMs: Long): Boolean = false
                    }
                },
            context = context,
            restoreMaintenanceCoordinator = restoreMaintenanceCoordinator,
            clock = Clock.fixed(Instant.parse("2026-08-31T12:00:00Z"), ZoneOffset.UTC),
        )
    }
}
