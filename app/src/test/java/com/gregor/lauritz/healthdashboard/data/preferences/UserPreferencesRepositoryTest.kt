package com.gregor.lauritz.healthdashboard.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesDataStoreFile
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals

@HiltAndroidTest
class UserPreferencesRepositoryTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    private lateinit var context: Context
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: UserPreferencesRepository

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        dataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()),
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
