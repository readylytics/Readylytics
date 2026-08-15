package app.readylytics.health.workers

import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import app.readylytics.health.data.local.DataRollupManager
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DataRollupWorkerTest {
    private val rollupManager = mockk<DataRollupManager>(relaxed = true)
    private val rollupManagerLazy = mockk<Lazy<DataRollupManager>>()
    private val workerParams = mockk<WorkerParameters>(relaxed = true)

    @Before
    fun setUp() {
        every { workerParams.taskExecutor } returns mockk(relaxed = true)
        every { rollupManagerLazy.get() } returns rollupManager
    }

    @Test
    fun `doWork triggers rollup and returns success`() =
        runBlocking {
            coEvery { rollupManager.rollupExpiredHotTier(any()) } returns 500

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

    private fun createWorker() =
        DataRollupWorker(
            context = ApplicationProvider.getApplicationContext(),
            params = workerParams,
            rollupManager = rollupManagerLazy,
        )
}
