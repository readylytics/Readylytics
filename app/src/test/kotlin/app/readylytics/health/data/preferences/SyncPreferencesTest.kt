package app.readylytics.health.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.domain.scoring.LoadSourceMode
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
class SyncPreferencesTest {
    private lateinit var dataStore: DataStore<UserPreferencesProto>
    private lateinit var syncPreferences: SyncPreferences

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fileName = "sync_prefs_${System.nanoTime()}.pb"
        dataStore =
            DataStoreFactory.create(
                serializer = UserPreferencesSerializer,
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                produceFile = { context.dataStoreFile(fileName) },
            )
        syncPreferences = SyncPreferences(dataStore)
    }

    @Test
    fun `bootstrap sets WORKOUT_ONLY when unset and workout-only history exists`() =
        runTest {
            syncPreferences.bootstrapPaiSourceModeIfUnset(hasWorkoutOnlyHistory = true)

            val proto = dataStore.data.first()
            assertEquals(LoadSourceModeProto.LOAD_SOURCE_WORKOUT_ONLY, proto.paiSourceMode)
            assertEquals(LoadSourceMode.WORKOUT_ONLY, proto.toDomainModel().paiSourceMode)
        }

    @Test
    fun `bootstrap sets EVERYDAY_HEART_RATE explicitly when unset and no workout-only history`() =
        runTest {
            syncPreferences.bootstrapPaiSourceModeIfUnset(hasWorkoutOnlyHistory = false)

            val proto = dataStore.data.first()
            assertEquals(LoadSourceModeProto.LOAD_SOURCE_EVERYDAY_HEART_RATE, proto.paiSourceMode)
            assertEquals(LoadSourceMode.EVERYDAY_HEART_RATE, proto.toDomainModel().paiSourceMode)
        }

    @Test
    fun `second invocation is a no-op once resolved to WORKOUT_ONLY`() =
        runTest {
            syncPreferences.bootstrapPaiSourceModeIfUnset(hasWorkoutOnlyHistory = true)
            // Second call, even with different history signal, must not change the persisted value.
            syncPreferences.bootstrapPaiSourceModeIfUnset(hasWorkoutOnlyHistory = false)

            val proto = dataStore.data.first()
            assertEquals(LoadSourceModeProto.LOAD_SOURCE_WORKOUT_ONLY, proto.paiSourceMode)
        }

    @Test
    fun `second invocation is a no-op once resolved to EVERYDAY_HEART_RATE`() =
        runTest {
            syncPreferences.bootstrapPaiSourceModeIfUnset(hasWorkoutOnlyHistory = false)
            // Second call, even with different history signal, must not change the persisted value.
            syncPreferences.bootstrapPaiSourceModeIfUnset(hasWorkoutOnlyHistory = true)

            val proto = dataStore.data.first()
            assertEquals(LoadSourceModeProto.LOAD_SOURCE_EVERYDAY_HEART_RATE, proto.paiSourceMode)
        }
}
