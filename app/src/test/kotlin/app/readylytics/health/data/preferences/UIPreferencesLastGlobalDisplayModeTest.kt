package app.readylytics.health.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.data.device.HealthDeviceRepository
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UIPreferencesLastGlobalDisplayModeTest {
    private lateinit var dataStore: DataStore<UserPreferencesProto>
    private lateinit var uiPreferences: UIPreferences

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fileName = "last_global_display_mode_prefs_${System.nanoTime()}.pb"
        dataStore =
            DataStoreFactory.create(
                serializer = UserPreferencesSerializer,
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                produceFile = { context.dataStoreFile(fileName) },
            )
        uiPreferences = UIPreferences(dataStore, mockk<HealthDeviceRepository>(relaxed = true))
    }

    @Test
    fun `lastGlobalDisplayMode defaults to null`() =
        runTest {
            val prefs = dataStore.data.first().toDomainModel()
            assertNull(prefs.lastGlobalDisplayMode)
        }

    @Test
    fun `updateLastGlobalDisplayMode persists and toDomainModel exposes it`() =
        runTest {
            uiPreferences.updateLastGlobalDisplayMode(DashboardCardDisplayMode.GAUGE)

            val prefs = dataStore.data.first().toDomainModel()
            assertEquals(DashboardCardDisplayMode.GAUGE, prefs.lastGlobalDisplayMode)
        }

    @Test
    fun `updateLastGlobalDisplayMode with null clears it back to unset`() =
        runTest {
            uiPreferences.updateLastGlobalDisplayMode(DashboardCardDisplayMode.BAR)
            uiPreferences.updateLastGlobalDisplayMode(null)

            val prefs = dataStore.data.first().toDomainModel()
            assertNull(prefs.lastGlobalDisplayMode)
        }
}
