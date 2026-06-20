package app.readylytics.health.workers

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.readylytics.health.domain.sync.ForegroundSyncController
import app.readylytics.health.domain.sync.FullHistoricalResyncUseCase
import io.mockk.every
import io.mockk.mockk
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
}
