package app.readylytics.health.workers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import app.readylytics.health.core.healthconnect.domain.sync.ForegroundSyncController
import app.readylytics.health.core.healthconnect.domain.sync.HealthSyncUseCase
import app.readylytics.health.core.model.workers.WorkerScheduler
import app.readylytics.health.domain.migration.DatabaseReadiness
import app.readylytics.health.domain.migration.DatabaseReadinessInspector
import app.readylytics.health.domain.repository.HealthConnectPermissionRevokedException
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
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class PeriodicHealthSyncWorkerTest {
    private lateinit var context: Context
    private lateinit var workerParams: WorkerParameters
    private val healthSyncUseCase = mockk<HealthSyncUseCase>()
    private val healthSyncUseCaseLazy = mockk<Lazy<HealthSyncUseCase>>()
    private val databaseReadinessGate = mockk<DatabaseReadinessInspector>()
    private val foregroundSyncController = mockk<ForegroundSyncController>(relaxed = true)
    private val foregroundSyncControllerLazy = mockk<Lazy<ForegroundSyncController>>()
    private val workerScheduler = mockk<WorkerScheduler>(relaxed = true)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        workerParams = mockk(relaxed = true)
        every { workerParams.taskExecutor } returns mockk(relaxed = true)
        every { healthSyncUseCaseLazy.get() } returns healthSyncUseCase
        every { foregroundSyncControllerLazy.get() } returns foregroundSyncController
        every { databaseReadinessGate.inspect() } returns DatabaseReadiness.Ready
    }

    @Test
    fun `doWork returns success when sync succeeds`() =
        runBlocking {
            coEvery { healthSyncUseCase.sync(windowDays = 2) } returns
                app.readylytics.health.domain.model.Result
                    .Success(Unit)

            val worker = createWorker()
            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            verify(exactly = 1) { foregroundSyncControllerLazy.get() }
            verify(exactly = 1) { foregroundSyncController.onBackgroundRecalcStarted() }
            verify(exactly = 1) { foregroundSyncController.onBackgroundRecalcFinished(true) }
        }

    @Test
    fun `doWork returns retry when sync fails`() =
        runBlocking {
            coEvery { healthSyncUseCase.sync(windowDays = 2) } returns
                app.readylytics.health.domain.model.Result
                    .Failure("error", "network error")

            val worker = createWorker()
            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.retry(), result)
        }

    @Test
    fun `doWork returns failure when permission is revoked`() =
        runBlocking {
            coEvery { healthSyncUseCase.sync(windowDays = 2) } throws
                HealthConnectPermissionRevokedException(SecurityException("permission revoked"))

            val worker = createWorker()
            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.failure(), result)
        }

    @Test
    fun `doWork returns retry when other exception thrown`() =
        runBlocking {
            coEvery { healthSyncUseCase.sync(windowDays = 2) } throws RuntimeException("unknown error")

            val worker = createWorker()
            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.retry(), result)
        }

    @Test
    fun `doWork schedules historical resync when bounded sync detects older changes`() =
        runBlocking {
            coEvery { healthSyncUseCase.sync(windowDays = 2) } returns
                app.readylytics.health.domain.model.Result
                    .Failure("Requires historical resync", "REQUIRES_HISTORICAL_RESYNC")

            val result = createWorker().doWork()

            verify(exactly = 1) { workerScheduler.scheduleResyncWorker() }
            verify(exactly = 1) { foregroundSyncController.onBackgroundRecalcFinished(false) }
            assertEquals(ListenableWorker.Result.success(), result)
        }

    @Test
    fun `doWork retries without resolving Room dependency when database is not ready`() =
        runBlocking {
            every { databaseReadinessGate.inspect() } returns DatabaseReadiness.MigrationRequired(6)

            val result = createWorker().doWork()

            assertEquals(ListenableWorker.Result.retry(), result)
            verify(exactly = 0) { healthSyncUseCaseLazy.get() }
            verify(exactly = 0) { foregroundSyncControllerLazy.get() }
        }

    private fun createWorker() =
        PeriodicHealthSyncWorker(
            appContext = context,
            params = workerParams,
            healthSyncUseCase = healthSyncUseCaseLazy,
            foregroundSyncController = foregroundSyncControllerLazy,
            workerScheduler = workerScheduler,
            databaseReadinessGate = databaseReadinessGate,
        )
}
