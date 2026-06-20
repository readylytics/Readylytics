package app.readylytics.health.workers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import app.readylytics.health.domain.repository.HealthConnectPermissionRevokedException
import app.readylytics.health.domain.sync.ForegroundSyncController
import app.readylytics.health.domain.sync.HealthSyncUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class PeriodicHealthSyncWorkerTest {
    private lateinit var context: Context
    private lateinit var workerParams: WorkerParameters
    private val healthSyncUseCase = mockk<HealthSyncUseCase>()
    private val foregroundSyncController = mockk<ForegroundSyncController>(relaxed = true)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        workerParams = mockk(relaxed = true)
        every { workerParams.taskExecutor } returns mockk(relaxed = true)
    }

    @Test
    fun `doWork returns success when sync succeeds`() =
        runBlocking {
            coEvery { healthSyncUseCase.sync(windowDays = 2) } returns
                app.readylytics.health.domain.model.Result
                    .Success(Unit)

            val worker = PeriodicHealthSyncWorker(context, workerParams, healthSyncUseCase, foregroundSyncController)
            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)
        }

    @Test
    fun `doWork returns retry when sync fails`() =
        runBlocking {
            coEvery { healthSyncUseCase.sync(windowDays = 2) } returns
                app.readylytics.health.domain.model.Result
                    .Failure("error", "network error")

            val worker = PeriodicHealthSyncWorker(context, workerParams, healthSyncUseCase, foregroundSyncController)
            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.retry(), result)
        }

    @Test
    fun `doWork returns failure when permission is revoked`() =
        runBlocking {
            coEvery { healthSyncUseCase.sync(windowDays = 2) } throws
                HealthConnectPermissionRevokedException(SecurityException("permission revoked"))

            val worker = PeriodicHealthSyncWorker(context, workerParams, healthSyncUseCase, foregroundSyncController)
            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.failure(), result)
        }

    @Test
    fun `doWork returns retry when other exception thrown`() =
        runBlocking {
            coEvery { healthSyncUseCase.sync(windowDays = 2) } throws RuntimeException("unknown error")

            val worker = PeriodicHealthSyncWorker(context, workerParams, healthSyncUseCase, foregroundSyncController)
            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.retry(), result)
        }
}
