package app.readylytics.health.performance

import androidx.datastore.core.DataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.readylytics.health.data.preferences.UserPreferencesSerializer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class StartupDataStoreTest {
    @Test
    fun dataStoreReadTime() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testFile = File(context.filesDir, "test_user_preferences_perf.pb")
        testFile.delete()
        try {
            runBlocking {
                val dataStore =
                    DataStoreFactory.create(
                        serializer = UserPreferencesSerializer,
                        produceFile = { testFile },
                    )

                val start = System.nanoTime()
                val prefs = dataStore.data.first()
                val elapsedMs = (System.nanoTime() - start) / 1_000_000

                assertNotNull(prefs)
                // Generous ceiling: this is a cold-start sanity check, not a performance
                // regression gate (that belongs in :benchmark's macrobenchmarks). Shared CI
                // runners see enough scheduling/IO noise on a first disk read + classloading
                // that a tight threshold (previously 50ms) flakes on CPU contention alone.
                assertTrue(
                    "DataStore read should be <200ms, was ${elapsedMs}ms",
                    elapsedMs < 200,
                )
            }
        } finally {
            testFile.delete()
        }
    }
}
