package app.readylytics.health.workers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import app.readylytics.health.data.local.RetentionCleanup
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.domain.preferences.UserPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class DataCleanupWorkerTest {
    private lateinit var context: Context
    private lateinit var workerParams: WorkerParameters
    private val retentionCleanup = mockk<RetentionCleanup>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        workerParams = mockk(relaxed = true)
        every { workerParams.taskExecutor } returns mockk(relaxed = true)
    }

    @Test
    fun `doWork returns success and deletes before cutoff when retention is enabled`() =
        runBlocking {
            val prefs = UserPreferences(retentionDaysEnabled = true, retentionDays = 30)
            every { settingsRepo.userPreferences } returns flowOf(prefs)

            val worker = DataCleanupWorker(context, workerParams, retentionCleanup, settingsRepo)
            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            coVerify(exactly = 1) { retentionCleanup.deleteBefore(any()) }
        }

    @Test
    fun `doWork returns success and does not delete when retention is disabled`() =
        runBlocking {
            val prefs = UserPreferences(retentionDaysEnabled = false)
            every { settingsRepo.userPreferences } returns flowOf(prefs)

            val worker = DataCleanupWorker(context, workerParams, retentionCleanup, settingsRepo)
            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            coVerify(exactly = 0) { retentionCleanup.deleteBefore(any()) }
        }

    @Test
    fun `doWork returns failure when cleanup throws an exception`() =
        runBlocking {
            val prefs = UserPreferences(retentionDaysEnabled = true, retentionDays = 30)
            every { settingsRepo.userPreferences } returns flowOf(prefs)
            coEvery { retentionCleanup.deleteBefore(any()) } throws RuntimeException("Database error")

            val worker = DataCleanupWorker(context, workerParams, retentionCleanup, settingsRepo)
            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.failure(), result)
        }
}
