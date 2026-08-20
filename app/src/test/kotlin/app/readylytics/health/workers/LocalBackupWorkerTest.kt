package app.readylytics.health.workers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import app.readylytics.health.core.model.domain.migration.DatabaseReadiness
import app.readylytics.health.core.model.domain.migration.DatabaseReadinessInspector
import app.readylytics.health.data.backup.LocalBackupManager
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
    private val localBackupManagerLazy = mockk<Lazy<LocalBackupManager>>()
    private val databaseReadinessGate = mockk<DatabaseReadinessInspector>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        workerParams = mockk(relaxed = true)
        every { workerParams.taskExecutor } returns mockk(relaxed = true)
        every { localBackupManagerLazy.get() } returns localBackupManager
        every { databaseReadinessGate.inspect() } returns DatabaseReadiness.Ready
    }

    @Test
    fun `doWork returns success when backup succeeds`() =
        runBlocking {
            coEvery { localBackupManager.createBackup() } returns Result.success<java.io.File?>(null)

            val worker = createWorker()
            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)
        }

    @Test
    fun `doWork returns retry when backup fails with IOException`() =
        runBlocking {
            coEvery { localBackupManager.createBackup() } returns
                Result.failure<java.io.File?>(IOException("Disk full"))

            val worker = createWorker()
            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.retry(), result)
        }

    @Test
    fun `doWork returns failure when backup fails with non-IOException`() =
        runBlocking {
            coEvery { localBackupManager.createBackup() } returns
                Result.failure<java.io.File?>(RuntimeException("Encryption error"))

            val worker = createWorker()
            val result = worker.doWork()

            assertEquals(
                ListenableWorker.Result.failure(androidx.work.workDataOf("error" to "Local backup failed")),
                result,
            )
        }

    @Test
    fun `doWork retries without resolving Room dependency when database is not ready`() =
        runBlocking {
            every { databaseReadinessGate.inspect() } returns DatabaseReadiness.MigrationRequired(5)

            val result = createWorker().doWork()

            assertEquals(ListenableWorker.Result.retry(), result)
            verify(exactly = 0) { localBackupManagerLazy.get() }
        }

    private fun createWorker() = LocalBackupWorker(context, workerParams, localBackupManagerLazy, databaseReadinessGate)
}
