package com.gregor.lauritz.healthdashboard.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
class UserPreferencesRepositoryTest {

    private lateinit var context: Context
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: UserPreferencesRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        dataStore = PreferenceDataStoreFactory.create(
            corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { context.preferencesDataStoreFile("test_prefs") }
        )
        repository = UserPreferencesRepository(dataStore)
    }

    @Test
    fun `retention days clamps to min 180`() = runTest {
        repository.updateRetentionDays(100)
        val prefs = repository.userPreferences.first()
        assertEquals(180, prefs.retentionDays)
    }

    @Test
    fun `retention days clamps to max 1095`() = runTest {
        repository.updateRetentionDays(2000)
        val prefs = repository.userPreferences.first()
        assertEquals(1095, prefs.retentionDays)
    }

    @Test
    fun `retention days accepts valid range 180-1095`() = runTest {
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
    fun `retention enabled toggle works`() = runTest {
        repository.updateRetentionDaysEnabled(false)
        var prefs = repository.userPreferences.first()
        assertEquals(false, prefs.retentionDaysEnabled)

        repository.updateRetentionDaysEnabled(true)
        prefs = repository.userPreferences.first()
        assertEquals(true, prefs.retentionDaysEnabled)
    }

    @Test
    fun `default retention days is 365`() = runTest {
        val prefs = repository.userPreferences.first()
        assertEquals(365, prefs.retentionDays)
    }

    @Test
    fun `default retention enabled is true`() = runTest {
        val prefs = repository.userPreferences.first()
        assertEquals(true, prefs.retentionDaysEnabled)
    }
}
