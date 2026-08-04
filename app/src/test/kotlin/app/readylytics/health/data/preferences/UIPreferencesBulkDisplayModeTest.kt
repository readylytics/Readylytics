package app.readylytics.health.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.data.device.HealthDeviceRepository
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UIPreferencesBulkDisplayModeTest {
    private lateinit var dataStore: DataStore<UserPreferencesProto>
    private lateinit var uiPreferences: UIPreferences

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fileName = "bulk_display_mode_prefs_${System.nanoTime()}.pb"
        dataStore =
            DataStoreFactory.create(
                serializer = UserPreferencesSerializer,
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                produceFile = { context.dataStoreFile(fileName) },
            )
        uiPreferences = UIPreferences(dataStore, mockk<HealthDeviceRepository>(relaxed = true))
    }

    @Test
    fun `bulkDisplayModeNoticeDismissed defaults to false`() =
        runTest {
            val prefs = dataStore.data.first().toDomainModel()
            assertFalse(prefs.bulkDisplayModeNoticeDismissed)
        }

    @Test
    fun `updateBulkDisplayModeNoticeDismissed persists and toDomainModel exposes it`() =
        runTest {
            uiPreferences.updateBulkDisplayModeNoticeDismissed(true)

            val prefs = dataStore.data.first().toDomainModel()
            assertTrue(prefs.bulkDisplayModeNoticeDismissed)
        }
}
