package app.readylytics.health.workers

import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import app.readylytics.health.core.database.data.local.DataRollupManager
import app.readylytics.health.core.model.domain.sync.ScoreInvalidation
import app.readylytics.health.core.model.workers.WorkerScheduler
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class DataRollupWorkerTest {
    private val rollupManager = mockk<DataRollupManager>(relaxed = true)
    private val rollupManagerLazy = mockk<Lazy<DataRollupManager>>()
    private val workerScheduler = mockk<WorkerScheduler>(relaxed = true)
    private val workerSchedulerLazy = mockk<Lazy<WorkerScheduler>>()
    private val workerParams = mockk<WorkerParameters>(relaxed = true)
    private val fixedClock = Clock.fixed(Instant.parse("2026-08-31T12:00:00Z"), ZoneId.of("UTC"))

    @Before
    fun setUp() {
        every { workerParams.taskExecutor } returns mockk(relaxed = true)
        every { rollupManagerLazy.get() } returns rollupManager
        every { workerSchedulerLazy.get() } returns workerScheduler
    }

    @Test
    fun `doWork triggers rollup and returns success`() =
        runBlocking {
            coEvery { rollupManager.rollupExpiredHotTier(any()) } returns
                ScoreInvalidation.AffectedRange(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 2))

            val result = createWorker().doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            coVerify(exactly = 1) { rollupManager.rollupExpiredHotTier(any()) }
        }

    @Test
    fun `a rollup that touched days enqueues exactly one bounded recompute`() =
        runBlocking {
            coEvery { rollupManager.rollupExpiredHotTier(any()) } returns
                ScoreInvalidation.AffectedRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 10))

            createWorker().doWork()

            verify(exactly = 1) {
                workerScheduler.scheduleResyncWorker(
                    recomputeOnly = true,
                    startDate = LocalDate.of(2026, 1, 1),
                    endDate = LocalDate.of(2026, 1, 10).plusDays(ScoreInvalidation.MAX_DEPENDENT_WINDOW_DAYS),
                )
            }
        }

    @Test
    fun `a no-op rollup enqueues nothing`() =
        runBlocking {
            coEvery { rollupManager.rollupExpiredHotTier(any()) } returns null

            createWorker().doWork()

            verify(exactly = 0) { workerScheduler.scheduleResyncWorker(any(), any(), any()) }
        }

    @Test
    fun `doWork retries when rollup throws`() =
        runBlocking {
            coEvery { rollupManager.rollupExpiredHotTier(any()) } throws RuntimeException("boom")

            val result = createWorker().doWork()

            assertEquals(ListenableWorker.Result.retry(), result)
        }

    private fun createWorker() =
        DataRollupWorker(
            context = ApplicationProvider.getApplicationContext(),
            params = workerParams,
            rollupManager = rollupManagerLazy,
            workerScheduler = workerSchedulerLazy,
            clock = fixedClock,
        )
}
