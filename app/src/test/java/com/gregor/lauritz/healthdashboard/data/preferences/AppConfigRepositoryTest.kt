package com.gregor.lauritz.healthdashboard.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
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
class AppConfigRepositoryTest {

    private lateinit var context: Context
    private lateinit var dataStore: DataStore<UserPreferencesProto>
    private lateinit var repository: AppConfigRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        dataStore = DataStoreFactory.create(
            serializer = UserPreferencesSerializer,
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { context.dataStoreFile("test_config.pb") }
        )
        repository = AppConfigRepository(dataStore)
    }

    @Test
    fun `default dynamic color enabled is true`() = runTest {
        val enabled = repository.dynamicColorEnabled.first()
        assertEquals(true, enabled)
    }

    @Test
    fun `dynamic color enabled toggle works`() = runTest {
        repository.updateDynamicColorEnabled(false)
        var enabled = repository.dynamicColorEnabled.first()
        assertEquals(false, enabled)

        repository.updateDynamicColorEnabled(true)
        enabled = repository.dynamicColorEnabled.first()
        assertEquals(true, enabled)
    }
}
