package app.readylytics.health.workers

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import app.readylytics.health.data.preferences.BackupSchedule
import dagger.Lazy
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Before
import org.junit.Test

class WorkerSchedulerTest {
    private val workManager = mockk<WorkManager>(relaxed = true)
    private val workManagerLazy = mockk<Lazy<WorkManager>>()
    private lateinit var scheduler: WorkerScheduler

    @Before
    fun setUp() {
        every { workManagerLazy.get() } returns workManager
        scheduler = WorkerScheduler(workManagerLazy)
    }

    @Test
    fun `scheduleResyncWorker enqueues unique work`() {
        scheduler.scheduleResyncWorker()
        verify(exactly = 1) {
            workManager.enqueueUniqueWork(
                WorkerScheduler.RESYNC_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                any<androidx.work.OneTimeWorkRequest>(),
            )
        }
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
        scheduler.schedulePeriodicSync(15L)
        verify(exactly = 1) {
            workManager.enqueueUniquePeriodicWork(
                WorkerScheduler.PERIODIC_SYNC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                any<androidx.work.PeriodicWorkRequest>(),
            )
        }
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
