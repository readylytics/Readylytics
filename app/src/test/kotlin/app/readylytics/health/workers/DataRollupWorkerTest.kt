package app.readylytics.health.workers

import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import app.readylytics.health.core.database.data.local.DataRollupManager
import app.readylytics.health.core.model.domain.sync.ScoreInvalidation
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
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
    private val workerParams = mockk<WorkerParameters>(relaxed = true)
    private val fixedClock = Clock.fixed(Instant.parse("2026-08-31T12:00:00Z"), ZoneId.of("UTC"))

    @Before
    fun setUp() {
        every { workerParams.taskExecutor } returns mockk(relaxed = true)
        every { rollupManagerLazy.get() } returns rollupManager
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
    fun `doWork retries when rollup throws`() =
        runBlocking {
            coEvery { rollupManager.rollupExpiredHotTier(any()) } throws RuntimeException("boom")

            val result = createWorker().doWork()

            assertEquals(ListenableWorker.Result.retry(), result)
        }

    @Test
    fun `rollup cuts off at the hot-tier boundary`() =
        runBlocking {
            val cutoffSlot = slot<Long>()
            coEvery { rollupManager.rollupExpiredHotTier(capture(cutoffSlot)) } returns null

            createWorker().doWork()

            assertEquals(
                app.readylytics.health.core.model.domain.util.RetentionBounds.resolveHotTierCutoffMs(
                    fixedClock.instant(),
                ),
                cutoffSlot.captured,
            )
        }

    private fun createWorker() =
        DataRollupWorker(
            context = ApplicationProvider.getApplicationContext(),
            params = workerParams,
            rollupManager = rollupManagerLazy,
            clock = fixedClock,
        )
}
