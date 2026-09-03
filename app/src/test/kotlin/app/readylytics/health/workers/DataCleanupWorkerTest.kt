package app.readylytics.health.workers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import app.readylytics.health.core.database.data.local.RetentionCleanup
import app.readylytics.health.core.model.domain.migration.DatabaseReadiness
import app.readylytics.health.core.model.domain.migration.DatabaseReadinessInspector
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.sync.ScoreInvalidation
import app.readylytics.health.core.model.workers.WorkerScheduler
import app.readylytics.health.data.preferences.SettingsRepository
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class DataCleanupWorkerTest {
    private lateinit var context: Context
    private lateinit var workerParams: WorkerParameters
    private val retentionCleanup = mockk<RetentionCleanup>(relaxed = true)
    private val retentionCleanupLazy = mockk<Lazy<RetentionCleanup>>()
    private val databaseReadinessGate = mockk<DatabaseReadinessInspector>()
    private val settingsRepo = mockk<SettingsRepository>()
    private val workerScheduler = mockk<WorkerScheduler>(relaxed = true)
    private val workerSchedulerLazy = mockk<Lazy<WorkerScheduler>>()
    private val fixedClock = Clock.fixed(Instant.parse("2026-08-31T12:00:00Z"), ZoneId.of("UTC"))

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        workerParams = mockk(relaxed = true)
        every { workerParams.taskExecutor } returns mockk(relaxed = true)
        every { retentionCleanupLazy.get() } returns retentionCleanup
        every { databaseReadinessGate.inspect() } returns DatabaseReadiness.Ready
        every { workerSchedulerLazy.get() } returns workerScheduler
    }

    @Test
    fun `doWork returns success and deletes before cutoff when retention is enabled`() =
        runBlocking {
            val prefs = UserPreferences(retentionDaysEnabled = true, retentionDays = 30)
            every { settingsRepo.userPreferences } returns flowOf(prefs)

            val worker = createWorker()
            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            coVerify(exactly = 1) { retentionCleanup.deleteBefore(any()) }
        }

    @Test
    fun `doWork returns success and does not delete when retention is disabled`() =
        runBlocking {
            val prefs = UserPreferences(retentionDaysEnabled = false)
            every { settingsRepo.userPreferences } returns flowOf(prefs)

            val worker = createWorker()
            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            coVerify(exactly = 0) { retentionCleanup.deleteBefore(any()) }
            verify(exactly = 0) { workerScheduler.scheduleResyncWorker(any(), any(), any()) }
        }

    @Test
    fun `doWork returns failure when cleanup throws an exception`() =
        runBlocking {
            val prefs = UserPreferences(retentionDaysEnabled = true, retentionDays = 30)
            every { settingsRepo.userPreferences } returns flowOf(prefs)
            coEvery { retentionCleanup.deleteBefore(any()) } throws RuntimeException("Database error")

            val worker = createWorker()
            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.failure(), result)
        }

    @Test
    fun `doWork retries without resolving Room dependency when database is not ready`() =
        runBlocking {
            every { databaseReadinessGate.inspect() } returns DatabaseReadiness.Failed("locked")

            val result = createWorker().doWork()

            assertEquals(ListenableWorker.Result.retry(), result)
            verify(exactly = 0) { retentionCleanupLazy.get() }
        }

    @Test
    fun `a cleanup that touched data enqueues exactly one bounded recompute`() =
        runBlocking {
            val prefs = UserPreferences(retentionDaysEnabled = true, retentionDays = 30)
            every { settingsRepo.userPreferences } returns flowOf(prefs)
            coEvery { retentionCleanup.deleteBefore(any()) } returns
                ScoreInvalidation.AffectedRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31))

            createWorker().doWork()

            verify(exactly = 1) {
                workerScheduler.scheduleResyncWorker(
                    recomputeOnly = true,
                    startDate = LocalDate.of(2026, 1, 1),
                    endDate = LocalDate.of(2026, 1, 31).plusDays(ScoreInvalidation.MAX_DEPENDENT_WINDOW_DAYS),
                )
            }
        }

    @Test
    fun `a no-op cleanup enqueues nothing`() =
        runBlocking {
            val prefs = UserPreferences(retentionDaysEnabled = true, retentionDays = 30)
            every { settingsRepo.userPreferences } returns flowOf(prefs)
            coEvery { retentionCleanup.deleteBefore(any()) } returns null

            createWorker().doWork()

            verify(exactly = 0) { workerScheduler.scheduleResyncWorker(any(), any(), any()) }
        }

    private fun createWorker() =
        DataCleanupWorker(
            context = context,
            params = workerParams,
            retentionCleanup = retentionCleanupLazy,
            settingsRepo = settingsRepo,
            databaseReadinessGate = databaseReadinessGate,
            workerScheduler = workerSchedulerLazy,
            clock = fixedClock,
        )
}
