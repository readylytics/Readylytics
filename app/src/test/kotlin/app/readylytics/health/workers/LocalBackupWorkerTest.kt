package app.readylytics.health.workers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import app.readylytics.health.data.backup.LocalBackupManager
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class LocalBackupWorkerTest {
    private lateinit var context: Context
    private lateinit var mockBackupManager: LocalBackupManager
    private lateinit var workerParams: WorkerParameters

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        mockBackupManager = mockk()
        workerParams = mockk(relaxed = true)
    }

    @Test
    fun doWork_success_returnsSuccess() =
        runTest {
            coEvery { mockBackupManager.createBackup() } returns Result.success(File("backup.zip"))

            val worker = LocalBackupWorker(context, workerParams, mockBackupManager)
            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)
        }

    @Test
    fun doWork_ioException_returnsRetry() =
        runTest {
            coEvery { mockBackupManager.createBackup() } returns Result.failure(IOException("Disk full"))

            val worker = LocalBackupWorker(context, workerParams, mockBackupManager)
            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.retry(), result)
        }

    @Test
    fun doWork_otherException_returnsFailure() =
        runTest {
            val errorMessage = "Fatal error"
            coEvery { mockBackupManager.createBackup() } returns Result.failure(Exception(errorMessage))

            val worker = LocalBackupWorker(context, workerParams, mockBackupManager)
            val result = worker.doWork()

            assertTrue(result is ListenableWorker.Result.Failure)
            val outputData = result.outputData
            assertEquals(errorMessage, outputData.getString("error"))
        }
}
