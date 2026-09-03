package app.readylytics.health.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Ensures `android:enableOnBackInvokedCallback="true"` is declared in `AndroidManifest.xml`.
 *
 * Without this attribute, Android 13/14+ will not dispatch progressive back gesture events,
 * leaving all Navigation Compose `predictivePop*Transition` blocks completely inert.
 */
class PredictiveBackManifestTest {
    private val root = sequenceOf(File("."), File("..")).first { File(it, "settings.gradle.kts").exists() }

    @Test
    fun `manifest declares enableOnBackInvokedCallback true`() {
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        assertTrue(
            "AndroidManifest.xml must declare android:enableOnBackInvokedCallback=\"true\" under <application>",
            manifest.contains("""android:enableOnBackInvokedCallback="true""""),
        )
    }
}
