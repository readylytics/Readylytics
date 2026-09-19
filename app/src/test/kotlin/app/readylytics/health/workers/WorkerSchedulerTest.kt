package app.readylytics.health.workers

import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import app.readylytics.health.core.model.data.preferences.BackupSchedule
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.sync.HistoricalRunIdentity
import app.readylytics.health.core.model.domain.sync.ResyncCheckpoint
import app.readylytics.health.core.model.domain.sync.ResyncCheckpointStore
import app.readylytics.health.core.model.domain.sync.ResyncPhase
import app.readylytics.health.core.model.workers.WorkerScheduler
import dagger.Lazy
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class WorkerSchedulerTest {
    private val workManager = mockk<WorkManager>(relaxed = true)
    private val workManagerLazy = mockk<Lazy<WorkManager>>()
    private val checkpointStore = mockk<ResyncCheckpointStore>()
    private val checkpointStoreLazy = mockk<Lazy<ResyncCheckpointStore>>()
    private lateinit var scheduler: WorkerScheduler

    @Before
    fun setUp() {
        every { workManagerLazy.get() } returns workManager
        every { checkpointStoreLazy.get() } returns checkpointStore
        every { checkpointStore.checkpoint } returns flowOf(null)
        scheduler = WorkerSchedulerImpl(workManagerLazy, checkpointStoreLazy)
    }

    private fun savedRunIdentity(runId: String) =
        HistoricalRunIdentity.create(
            runId = runId,
            mode = HistoricalRunIdentity.MODE_FULL_INGEST,
            startDate = LocalDate.of(2026, 1, 1),
            endDate = LocalDate.of(2026, 1, 10),
            zoneId = ZoneId.of("Europe/Berlin"),
            prefs = UserPreferences(),
            resolvedHrMax = 180f,
            startedAtEpochMs = 0L,
        )

    @Test
    fun `full resync keeps existing unique work`() =
        runTest {
            val request = slot<OneTimeWorkRequest>()

            scheduler.scheduleResyncWorker(recomputeOnly = false)

            verify {
                workManager.enqueueUniqueWork(
                    WorkerScheduler.RESYNC_WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    capture(request),
                )
            }
            assertFalse(
                request.captured.workSpec.input
                    .getBoolean(HealthResyncWorker.KEY_RECOMPUTE_ONLY, true),
            )
        }

    @Test
    fun `scheduleResyncWorker threads the saved checkpoint's run id into the request`() =
        runTest {
            val runIdentity = savedRunIdentity("saved-run-123")
            every { checkpointStore.checkpoint } returns
                flowOf(
                    ResyncCheckpoint(
                        startDate = LocalDate.of(2026, 1, 1),
                        endDate = LocalDate.of(2026, 1, 10),
                        phase = ResyncPhase.INGEST,
                        nextDate = LocalDate.of(2026, 1, 3),
                        selectionHash = runIdentity.scoringSnapshotId,
                        runIdentity = runIdentity,
                    ),
                )
            val request = slot<OneTimeWorkRequest>()

            scheduler.scheduleResyncWorker(recomputeOnly = false)

            verify {
                workManager.enqueueUniqueWork(
                    WorkerScheduler.RESYNC_WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    capture(request),
                )
            }
            assertEquals(
                "saved-run-123",
                request.captured.workSpec.input
                    .getString(HealthResyncWorker.KEY_RUN_ID),
            )
        }

    @Test
    fun `scheduleResyncWorker omits the run id when no checkpoint is saved`() =
        runTest {
            val request = slot<OneTimeWorkRequest>()

            scheduler.scheduleResyncWorker(recomputeOnly = false)

            verify {
                workManager.enqueueUniqueWork(
                    WorkerScheduler.RESYNC_WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    capture(request),
                )
            }
            assertEquals(
                null,
                request.captured.workSpec.input
                    .getString(HealthResyncWorker.KEY_RUN_ID),
            )
        }

    @Test
    fun `database migration uses unique keep work with exponential backoff`() {
        val request = slot<OneTimeWorkRequest>()

        scheduler.scheduleDatabaseMigration()

        verify(exactly = 1) {
            workManager.enqueueUniqueWork(
                WorkerScheduler.DATABASE_MIGRATION_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                capture(request),
            )
        }
        assertEquals(DatabaseMigrationWorker::class.java.name, request.captured.workSpec.workerClassName)
        assertEquals(BackoffPolicy.EXPONENTIAL, request.captured.workSpec.backoffPolicy)
        assertEquals(30_000L, request.captured.workSpec.backoffDelayDuration)
        assertFalse(request.captured.workSpec.expedited)
    }

    @Test
    fun `recompute request appends behind active unique work`() =
        runTest {
            val request = slot<OneTimeWorkRequest>()

            scheduler.scheduleResyncWorker(recomputeOnly = true)

            verify {
                workManager.enqueueUniqueWork(
                    WorkerScheduler.RESYNC_WORK_NAME,
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    capture(request),
                )
            }
            assertTrue(
                request.captured.workSpec.input
                    .getBoolean(HealthResyncWorker.KEY_RECOMPUTE_ONLY, false),
            )
        }

    @Test
    fun `recompute request with a date range carries both epoch-day bounds as input data`() =
        runTest {
            val request = slot<OneTimeWorkRequest>()
            val startDate = java.time.LocalDate.of(2026, 1, 1)
            val endDate = java.time.LocalDate.of(2026, 2, 1)

            scheduler.scheduleResyncWorker(recomputeOnly = true, startDate = startDate, endDate = endDate)

            verify {
                workManager.enqueueUniqueWork(
                    WorkerScheduler.RESYNC_WORK_NAME,
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    capture(request),
                )
            }
            val input = request.captured.workSpec.input
            assertEquals(startDate.toEpochDay(), input.getLong(HealthResyncWorker.KEY_RECOMPUTE_START_EPOCH_DAY, -1L))
            assertEquals(endDate.toEpochDay(), input.getLong(HealthResyncWorker.KEY_RECOMPUTE_END_EPOCH_DAY, -1L))
        }

    @Test
    fun `recompute request without a date range omits the epoch-day keys`() =
        runTest {
            val request = slot<OneTimeWorkRequest>()

            scheduler.scheduleResyncWorker(recomputeOnly = true)

            verify {
                workManager.enqueueUniqueWork(
                    WorkerScheduler.RESYNC_WORK_NAME,
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    capture(request),
                )
            }
            val input = request.captured.workSpec.input
            assertEquals(-1L, input.getLong(HealthResyncWorker.KEY_RECOMPUTE_START_EPOCH_DAY, -1L))
            assertEquals(-1L, input.getLong(HealthResyncWorker.KEY_RECOMPUTE_END_EPOCH_DAY, -1L))
        }

    @Test
    fun `cancelResyncWorker cancels unique work`() {
        scheduler.cancelResyncWorker()
        verify(exactly = 1) {
            workManager.cancelUniqueWork(WorkerScheduler.RESYNC_WORK_NAME)
        }
    }

    @Test
    fun `scheduleBackupWorker cancels work on MANUAL schedule`() {
        scheduler.scheduleBackupWorker(BackupSchedule.MANUAL)
        verify(exactly = 1) {
            workManager.cancelUniqueWork(WorkerScheduler.LOCAL_BACKUP_WORK_NAME)
        }
    }

    @Test
    fun `scheduleBackupWorker enqueues unique periodic work for DAILY`() {
        scheduler.scheduleBackupWorker(BackupSchedule.DAILY)
        verify(exactly = 1) {
            workManager.enqueueUniquePeriodicWork(
                WorkerScheduler.LOCAL_BACKUP_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                any<androidx.work.PeriodicWorkRequest>(),
            )
        }
    }

    @Test
    fun `scheduleBirthdayWorker enqueues unique periodic work`() {
        scheduler.scheduleBirthdayWorker()
        verify(exactly = 1) {
            workManager.enqueueUniquePeriodicWork(
                WorkerScheduler.BIRTHDAY_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                any<androidx.work.PeriodicWorkRequest>(),
            )
        }
    }

    @Test
    fun `schedulePeriodicSync enqueues unique periodic work`() {
        val request = slot<PeriodicWorkRequest>()

        scheduler.schedulePeriodicSync(15L)

        verify(exactly = 1) {
            workManager.enqueueUniquePeriodicWork(
                WorkerScheduler.PERIODIC_SYNC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                capture(request),
            )
        }

        val constraints = request.captured.workSpec.constraints
        assertTrue(constraints.requiresBatteryNotLow())
        assertFalse(constraints.requiresCharging())
        assertFalse(constraints.requiresDeviceIdle())
    }

    @Test
    fun `cancelPeriodicSync cancels unique work`() {
        scheduler.cancelPeriodicSync()
        verify(exactly = 1) {
            workManager.cancelUniqueWork(WorkerScheduler.PERIODIC_SYNC_WORK_NAME)
        }
    }

    @Test
    fun `scheduleDataCleanupWorker enqueues unique periodic work`() {
        scheduler.scheduleDataCleanupWorker()
        verify(exactly = 1) {
            workManager.enqueueUniquePeriodicWork(
                WorkerScheduler.DATA_CLEANUP_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                any<androidx.work.PeriodicWorkRequest>(),
            )
        }
    }
}
