package com.gregor.lauritz.healthdashboard.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gregor.lauritz.healthdashboard.data.device.HealthDeviceRepository
import com.gregor.lauritz.healthdashboard.domain.model.HealthDataType
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UIPreferencesDeviceTest {
    private lateinit var dataStore: DataStore<UserPreferencesProto>
    private lateinit var uiPreferences: UIPreferences

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fileName = "device_prefs_${System.nanoTime()}.pb"
        dataStore =
            DataStoreFactory.create(
                serializer = UserPreferencesSerializer,
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                produceFile = { context.dataStoreFile(fileName) },
            )
        uiPreferences = UIPreferences(dataStore, mockk<HealthDeviceRepository>(relaxed = true))
    }

    @Test
    fun `updateDeviceForDataType stores selection and toDomainModel exposes it`() =
        runTest {
            uiPreferences.updateDeviceForDataType(HealthDataType.STEPS.name, "Pixel Watch")

            val prefs = dataStore.data.first().toDomainModel()
            assertEquals("Pixel Watch", prefs.deviceByDataType[HealthDataType.STEPS.name])
        }

    @Test
    fun `updateDeviceForDataType with null clears the selection`() =
        runTest {
            uiPreferences.updateDeviceForDataType(HealthDataType.STEPS.name, "Pixel Watch")
            uiPreferences.updateDeviceForDataType(HealthDataType.STEPS.name, null)

            val prefs = dataStore.data.first().toDomainModel()
            assertNull(prefs.deviceByDataType[HealthDataType.STEPS.name])
        }

    @Test
    fun `migration seeds all data types from legacy primary device and clears it`() =
        runTest {
            dataStore.updateData { it.toBuilder().setPrimaryDeviceName("Legacy Watch").build() }

            uiPreferences.migrateDeviceSelectionIfNeeded()

            val proto = dataStore.data.first()
            assertFalse(proto.hasPrimaryDeviceName())
            HealthDataType.entries.forEach { type ->
                assertEquals("Legacy Watch", proto.deviceByDataTypeMap[type.name])
            }
        }

    @Test
    fun `migration is a no-op when per-type selections already exist`() =
        runTest {
            uiPreferences.updateDeviceForDataType(HealthDataType.SLEEP.name, "Oura")
            dataStore.updateData { it.toBuilder().setPrimaryDeviceName("Legacy Watch").build() }

            uiPreferences.migrateDeviceSelectionIfNeeded()

            val proto = dataStore.data.first()
            // Existing per-type map preserved; legacy field left untouched.
            assertEquals("Oura", proto.deviceByDataTypeMap[HealthDataType.SLEEP.name])
            assertEquals(1, proto.deviceByDataTypeMap.size)
        }
}
