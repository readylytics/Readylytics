package app.readylytics.health.workers

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import app.readylytics.health.core.model.domain.migration.DatabaseMigrationProgress
import app.readylytics.health.core.model.domain.migration.V7MigrationPhase
import app.readylytics.health.core.model.domain.migration.V7MigrationResult
import app.readylytics.health.data.migration.V7DatabaseMigrator
import com.google.common.util.concurrent.Futures
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
class DatabaseMigrationWorkerTest {
    private lateinit var context: Context
    private lateinit var params: WorkerParameters
    private val migrator = mockk<V7DatabaseMigrator>()
    private val foregroundUpdater = mockk<androidx.work.ForegroundUpdater>()
    private val progressUpdater = mockk<androidx.work.ProgressUpdater>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        params = mockk(relaxed = true)
        every { params.taskExecutor } returns mockk(relaxed = true)
        every { params.foregroundUpdater } returns foregroundUpdater
        every { params.progressUpdater } returns progressUpdater
        every { foregroundUpdater.setForegroundAsync(any(), any(), any()) } returns
            Futures.immediateFuture(null)
        every { progressUpdater.updateProgress(any(), any(), any()) } returns
            Futures.immediateFuture(null)
    }

    @Test
    fun `getForegroundInfo uses distinct migration notification and data sync type`() =
        runBlocking {
            val info: ForegroundInfo = worker().getForegroundInfo()

            assertEquals(SyncNotifications.DATABASE_MIGRATION_NOTIFICATION_ID, info.notificationId)
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, info.foregroundServiceType)
            assertEquals(SyncNotifications.DATABASE_MIGRATION_CHANNEL_ID, info.notification.channelId)
        }

    @Test
    fun `progress and failure output keys stay stable`() {
        assertEquals("phase", DatabaseMigrationWorker.KEY_PHASE)
        assertEquals("copiedRows", DatabaseMigrationWorker.KEY_COPIED_ROWS)
        assertEquals("totalRows", DatabaseMigrationWorker.KEY_TOTAL_ROWS)
        assertEquals("requiredBytes", DatabaseMigrationWorker.KEY_REQUIRED_BYTES)
        assertEquals("availableBytes", DatabaseMigrationWorker.KEY_AVAILABLE_BYTES)
    }

    @Test
    fun `foreground starts before migration and progress updates foreground plus work data`() =
        runBlocking {
            val progress =
                DatabaseMigrationProgress(
                    phase = V7MigrationPhase.COPY_HEART_RATE,
                    copiedRows = 12L,
                    totalRows = 50L,
                )
            coEvery { migrator.migrate(any()) } coAnswers {
                firstArg<suspend (DatabaseMigrationProgress) -> Unit>().invoke(progress)
                V7MigrationResult.Complete
            }

            val result = worker().doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            coVerifyOrder {
                foregroundUpdater.setForegroundAsync(any(), any(), any())
                migrator.migrate(any())
            }
            coVerify {
                progressUpdater.updateProgress(
                    any(),
                    any(),
                    match {
                        it.getString(DatabaseMigrationWorker.KEY_PHASE) == progress.phase.name &&
                            it.getLong(DatabaseMigrationWorker.KEY_COPIED_ROWS, -1L) == 12L &&
                            it.getLong(DatabaseMigrationWorker.KEY_TOTAL_ROWS, -1L) == 50L
                    },
                )
            }
            coVerify(exactly = 2) { foregroundUpdater.setForegroundAsync(any(), any(), any()) }
        }

    @Test
    fun `insufficient space returns failure with required and available bytes`() =
        runBlocking {
            coEvery { migrator.migrate(any()) } returns V7MigrationResult.InsufficientSpace(900L, 400L)

            val result = worker().doWork()

            assertEquals(
                ListenableWorker.Result.failure(
                    Data
                        .Builder()
                        .putLong(DatabaseMigrationWorker.KEY_REQUIRED_BYTES, 900L)
                        .putLong(DatabaseMigrationWorker.KEY_AVAILABLE_BYTES, 400L)
                        .build(),
                ),
                result,
            )
        }

    @Test
    fun `ordinary migration failure retries`() =
        runBlocking {
            coEvery { migrator.migrate(any()) } returns V7MigrationResult.Failed("validation")

            assertEquals(ListenableWorker.Result.retry(), worker().doWork())
        }

    @Test
    fun `unexpected exception retries`() =
        runBlocking {
            coEvery { migrator.migrate(any()) } throws IllegalStateException("io")

            assertEquals(ListenableWorker.Result.retry(), worker().doWork())
        }

    @Test
    fun `cancellation is rethrown`() {
        runBlocking {
            coEvery { migrator.migrate(any()) } throws CancellationException("stop")

            assertFailsWith<CancellationException> { worker().doWork() }
        }
    }

    private fun worker() = DatabaseMigrationWorker(context, params, migrator)
}
