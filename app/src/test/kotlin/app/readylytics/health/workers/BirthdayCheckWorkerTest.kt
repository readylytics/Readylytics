package app.readylytics.health.workers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.domain.preferences.UserPreferences
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class BirthdayCheckWorkerTest {
    private lateinit var context: Context
    private lateinit var workerParams: WorkerParameters
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        workerParams = mockk(relaxed = true)
        every { workerParams.taskExecutor } returns mockk(relaxed = true)
    }

    @Test
    fun `doWork returns success and skips when birthday not configured`() =
        runBlocking {
            val prefs = UserPreferences(isBirthdayConfigured = false, birthDate = null)
            every { settingsRepo.userPreferences } returns flowOf(prefs)

            val worker = BirthdayCheckWorker(context, workerParams, settingsRepo)
            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            coVerify(exactly = 0) { settingsRepo.updateAge(any()) }
        }

    @Test
    fun `doWork returns success and updates age when age changes`() =
        runBlocking {
            // Birthdate is 30 years ago today
            val birthDate = LocalDate.now().minusYears(30).toString()
            val prefs = UserPreferences(isBirthdayConfigured = true, birthDate = birthDate, age = 29)
            every { settingsRepo.userPreferences } returns flowOf(prefs)

            val worker = BirthdayCheckWorker(context, workerParams, settingsRepo)
            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            coVerify(exactly = 1) { settingsRepo.updateAge(30) }
        }

    @Test
    fun `doWork returns success and does not update age when age is correct`() =
        runBlocking {
            val birthDate = LocalDate.now().minusYears(30).toString()
            val prefs = UserPreferences(isBirthdayConfigured = true, birthDate = birthDate, age = 30)
            every { settingsRepo.userPreferences } returns flowOf(prefs)

            val worker = BirthdayCheckWorker(context, workerParams, settingsRepo)
            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success(), result)
            coVerify(exactly = 0) { settingsRepo.updateAge(any()) }
        }
}
