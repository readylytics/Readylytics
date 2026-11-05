package app.readylytics.health.architecture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ClockBindingOwnershipTest {
    private val root = sequenceOf(File("."), File("..")).first { File(it, "settings.gradle.kts").exists() }
    private val appMain = File(root, "app/src/main/kotlin/app/readylytics/health")

    @Test
    fun `provideClock is bound in UtilModule and not in DataStoreModule`() {
        val util = File(appMain, "di/UtilModule.kt").readText()
        val dataStore = File(appMain, "di/DataStoreModule.kt").readText()

        assertTrue("UtilModule must provide Clock", util.contains("fun provideClock(): Clock"))
        assertFalse("DataStoreModule must not mention Clock", dataStore.contains("Clock"))
    }

    @Test
    fun `MainActivity does not hard-code Dispatchers IO`() {
        val activity = File(appMain, "MainActivity.kt").readText()

        assertFalse("MainActivity must not use Dispatchers.IO", activity.contains("Dispatchers.IO"))
        assertTrue("MainActivity must inject @IoDispatcher", activity.contains("@IoDispatcher"))
    }
}
