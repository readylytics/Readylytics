package app.readylytics.health.workers

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.readylytics.health.domain.repository.HealthConnectPermissionRevokedException
import app.readylytics.health.domain.sync.ForegroundSyncController
import app.readylytics.health.domain.sync.FullHistoricalResyncUseCase
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class HealthResyncWorkerTest {
    private lateinit var context: Context
    private lateinit var workerParams: WorkerParameters
    private val useCase = mockk<FullHistoricalResyncUseCase>()
    private val foregroundSyncController = mockk<ForegroundSyncController>(relaxed = true)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        workerParams = mockk(relaxed = true)
        every { workerParams.taskExecutor } returns mockk(relaxed = true)

        val progressUpdater = mockk<androidx.work.ProgressUpdater>()
        every { workerParams.progressUpdater } returns progressUpdater
        every { progressUpdater.updateProgress(any(), any(), any()) } returns
            com.google.common.util.concurrent.Futures
                .immediateFuture(null)

        val foregroundUpdater = mockk<androidx.work.ForegroundUpdater>()
        every { workerParams.foregroundUpdater } returns foregroundUpdater
        every { foregroundUpdater.setForegroundAsync(any(), any(), any()) } returns
            com.google.common.util.concurrent.Futures
                .immediateFuture(null)
    }

    @Test
    fun `getForegroundInfo uses resync notification id and data sync service type`() =
        runBlocking {
            val worker = HealthResyncWorker(context, workerParams, useCase, foregroundSyncController)
            val foregroundInfo: ForegroundInfo = worker.getForegroundInfo()

            assertEquals(SyncNotifications.NOTIFICATION_ID, foregroundInfo.notificationId)
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, foregroundInfo.foregroundServiceType)
            assertTrue(foregroundInfo.notification.channelId == SyncNotifications.CHANNEL_ID)
        }

    @Test
    fun `worker progress keys stay stable`() {
        assertEquals("current", HealthResyncWorker.KEY_CURRENT)
        assertEquals("total", HealthResyncWorker.KEY_TOTAL)
    }

    @Test
    fun `doWork reports progress and returns success when resync usecase succeeds`() =
        runBlocking {
            coEvery { useCase.execute(any()) } answers {
                val progressCallback = firstArg<(Int, Int) -> Unit>()
                progressCallback(1, 10)
                app.readylytics.health.domain.model.Result
                    .Success(Unit)
            }
            val worker = HealthResyncWorker(context, workerParams, useCase, foregroundSyncController)
            val result = worker.doWork()
            assertEquals(
                androidx.work.ListenableWorker.Result
                    .success(),
                result,
            )
        }

    @Test
    fun `doWork returns retry when resync usecase fails`() =
        runBlocking {
            coEvery { useCase.execute(any()) } returns
                app.readylytics.health.domain.model.Result
                    .Failure("error", "network error")
            val worker = HealthResyncWorker(context, workerParams, useCase, foregroundSyncController)
            val result = worker.doWork()
            assertEquals(
                androidx.work.ListenableWorker.Result
                    .retry(),
                result,
            )
        }

    @Test
    fun `doWork returns retry when resync usecase throws exception`() =
        runBlocking {
            coEvery { useCase.execute(any()) } throws RuntimeException("critical error")
            val worker = HealthResyncWorker(context, workerParams, useCase, foregroundSyncController)
            val result = worker.doWork()
            assertEquals(
                androidx.work.ListenableWorker.Result
                    .retry(),
                result,
            )
        }

    @Test
    fun `doWork returns terminal failure when Health Connect permission is revoked`() =
        runBlocking {
            coEvery { useCase.execute(any()) } throws
                HealthConnectPermissionRevokedException(SecurityException("permission revoked"))
            val worker = HealthResyncWorker(context, workerParams, useCase, foregroundSyncController)

            val result = worker.doWork()

            assertEquals(
                androidx.work.ListenableWorker.Result
                    .failure(),
                result,
            )
        }
}
