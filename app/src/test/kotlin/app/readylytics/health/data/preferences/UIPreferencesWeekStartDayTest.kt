package app.readylytics.health.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.core.database.data.local.HealthDeviceRepository
import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import app.readylytics.health.core.model.domain.util.WeekBounds
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class UIPreferencesWeekStartDayTest {
    private lateinit var context: Context
    private lateinit var fileName: String
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStore: DataStore<UserPreferencesProto>
    private lateinit var uiPreferences: UIPreferences

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        fileName = "week_start_day_prefs_${System.nanoTime()}.pb"
        dataStoreScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStore = newDataStore(dataStoreScope)
        uiPreferences = UIPreferences(dataStore, mockk<HealthDeviceRepository>(relaxed = true))
    }

    private fun newDataStore(scope: CoroutineScope): DataStore<UserPreferencesProto> =
        DataStoreFactory.create(
            serializer = UserPreferencesSerializer,
            scope = scope,
            produceFile = { context.dataStoreFile(fileName) },
        )

    @Test
    fun `weekStartDay defaults to monday`() =
        runTest {
            val prefs = dataStore.data.first().toDomainModel()
            assertEquals(SettingsDefaults.WEEK_START_DAY, prefs.weekStartDay)
            assertEquals(DayOfWeek.MONDAY, prefs.weekStartDay)
        }

    @Test
    fun `updateWeekStartDay persists monday`() =
        runTest {
            uiPreferences.updateWeekStartDay(DayOfWeek.MONDAY)

            val prefs = dataStore.data.first().toDomainModel()
            assertEquals(DayOfWeek.MONDAY, prefs.weekStartDay)
        }

    @Test
    fun `updateWeekStartDay persists sunday`() =
        runTest {
            uiPreferences.updateWeekStartDay(DayOfWeek.SUNDAY)

            val prefs = dataStore.data.first().toDomainModel()
            assertEquals(DayOfWeek.SUNDAY, prefs.weekStartDay)
        }

    @Test
    fun `updateWeekStartDay persists saturday`() =
        runTest {
            uiPreferences.updateWeekStartDay(DayOfWeek.SATURDAY)

            val prefs = dataStore.data.first().toDomainModel()
            assertEquals(DayOfWeek.SATURDAY, prefs.weekStartDay)
        }

    @Test
    fun `weekStartDay survives a fresh DataStore instance pointed at the same file`() =
        runTest {
            uiPreferences.updateWeekStartDay(DayOfWeek.SUNDAY)

            // DataStore forbids two live instances on one file; the first is inactive only
            // once its scope's job completes, which also releases the active-file registry.
            dataStoreScope.coroutineContext.job.cancelAndJoin()

            val restarted = newDataStore(CoroutineScope(Dispatchers.IO + SupervisorJob()))
            val prefs = restarted.data.first().toDomainModel()

            assertEquals(DayOfWeek.SUNDAY, prefs.weekStartDay)
        }

    @Test
    fun `changing the setting changes the calculated week boundaries`() =
        runTest {
            val today = LocalDate.of(2026, 6, 4) // Thursday

            uiPreferences.updateWeekStartDay(DayOfWeek.MONDAY)
            val mondayPrefs = dataStore.data.first().toDomainModel()
            val mondayWeekStart = WeekBounds.weekStartOnOrBefore(today, mondayPrefs.weekStartDay)

            uiPreferences.updateWeekStartDay(DayOfWeek.SUNDAY)
            val sundayPrefs = dataStore.data.first().toDomainModel()
            val sundayWeekStart = WeekBounds.weekStartOnOrBefore(today, sundayPrefs.weekStartDay)

            assertEquals(LocalDate.of(2026, 6, 1), mondayWeekStart)
            assertEquals(LocalDate.of(2026, 5, 31), sundayWeekStart)
        }
}
