package app.readylytics.health.workers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import app.readylytics.health.data.backup.LocalBackupManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class LocalBackupWorkerTest {
    private lateinit var context: Context
    private lateinit var workerParams: WorkerParameters
    private val localBackupManager = mockk<LocalBackupManager>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        workerParams = mockk(relaxed = true)
        every { workerParams.taskExecutor } returns mockk(relaxed = true)
    }

    @Test
    fun `doWork returns success when backup succeeds`() =
        runBlocking {
            coEvery { localBackupManager.createBackup() } returns Result.success<java.io.File?>(null)

            val worker = LocalBackupWorker(context, workerParams, localBackupManager)
            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)
        }

    @Test
    fun `doWork returns retry when backup fails with IOException`() =
        runBlocking {
            coEvery { localBackupManager.createBackup() } returns
                Result.failure<java.io.File?>(IOException("Disk full"))

            val worker = LocalBackupWorker(context, workerParams, localBackupManager)
            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.retry(), result)
        }

    @Test
    fun `doWork returns failure when backup fails with non-IOException`() =
        runBlocking {
            coEvery { localBackupManager.createBackup() } returns
                Result.failure<java.io.File?>(RuntimeException("Encryption error"))

            val worker = LocalBackupWorker(context, workerParams, localBackupManager)
            val result = worker.doWork()

            assertEquals(
                ListenableWorker.Result.failure(androidx.work.workDataOf("error" to "Local backup failed")),
                result,
            )
        }
}
