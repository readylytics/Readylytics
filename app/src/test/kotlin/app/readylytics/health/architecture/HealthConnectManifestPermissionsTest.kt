package app.readylytics.health.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `READ_EXERCISE_ROUTES` is intentionally absent from every runtime permission request (see
 * `HealthConnectPermissionSetsTest`), which makes the manifest declaration easy to mistake for dead
 * weight. It is not: that declaration is the sole reason Health Connect shows the tri-state
 * "Access exercise routes" row under this app's "Additional access" page. Remove it and the user
 * loses the only way to grant routes permanently, and the per-session consent dialog stops working.
 */
class HealthConnectManifestPermissionsTest {
    private val root = sequenceOf(File("."), File("..")).first { File(it, "settings.gradle.kts").exists() }

    @Test
    fun `manifest declares exercise routes permission`() {
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        assertTrue(
            "AndroidManifest.xml must declare android.permission.health.READ_EXERCISE_ROUTES",
            manifest.contains("""android:name="android.permission.health.READ_EXERCISE_ROUTES""""),
        )
    }
}
