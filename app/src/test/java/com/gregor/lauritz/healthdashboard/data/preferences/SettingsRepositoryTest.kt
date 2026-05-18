package com.gregor.lauritz.healthdashboard.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsRepositoryTest {
    private lateinit var context: Context
    private lateinit var dataStore: DataStore<UserPreferencesProto>
    private lateinit var repository: SettingsRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        dataStore =
            DataStoreFactory.create(
                serializer = UserPreferencesSerializer,
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                produceFile = { context.dataStoreFile("test_settings.pb") },
            )
        repository =
            SettingsRepository(
                dataStore = dataStore,
                physiology = mockk<PhysiologyPreferences>(relaxed = true),
                thresholds = mockk<ThresholdPreferences>(relaxed = true),
                sleep = SleepPreferences(dataStore),
                ui = mockk<UIPreferences>(relaxed = true),
                sync = mockk<SyncPreferences>(relaxed = true),
                backup = mockk<BackupPreferences>(relaxed = true),
            )
    }

    @Test
    fun `retention days clamps to min 180`() =
        runTest {
            repository.updateRetentionDays(100)
            val prefs = repository.userPreferences.first()
            assertEquals(180, prefs.retentionDays)
        }

    @Test
    fun `retention days clamps to max 1095`() =
        runTest {
            repository.updateRetentionDays(2000)
            val prefs = repository.userPreferences.first()
            assertEquals(1095, prefs.retentionDays)
        }

    @Test
    fun `retention days accepts valid range 180-1095`() =
        runTest {
            repository.updateRetentionDays(365)
            var prefs = repository.userPreferences.first()
            assertEquals(365, prefs.retentionDays)

            repository.updateRetentionDays(180)
            prefs = repository.userPreferences.first()
            assertEquals(180, prefs.retentionDays)

            repository.updateRetentionDays(1095)
            prefs = repository.userPreferences.first()
            assertEquals(1095, prefs.retentionDays)
        }

    @Test
    fun `retention enabled toggle works`() =
        runTest {
            repository.updateRetentionDaysEnabled(false)
            var prefs = repository.userPreferences.first()
            assertEquals(false, prefs.retentionDaysEnabled)

            repository.updateRetentionDaysEnabled(true)
            prefs = repository.userPreferences.first()
            assertEquals(true, prefs.retentionDaysEnabled)
        }

    @Test
    fun `default retention days is 365`() =
        runTest {
            val prefs = repository.userPreferences.first()
            assertEquals(365, prefs.retentionDays)
        }

    @Test
    fun `default retention enabled is true`() =
        runTest {
            val prefs = repository.userPreferences.first()
            assertEquals(true, prefs.retentionDaysEnabled)
        }
}
