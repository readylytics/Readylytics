package app.readylytics.health.build

import org.junit.Test
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertTrue

class ConventionPluginPresenceTest {
    private val root: File =
        run {
            var dir: File? =
                File(
                    javaClass.protectionDomain.codeSource.location
                        .toURI(),
                ).parentFile
            while (dir != null && !File(dir, "settings.gradle.kts").exists()) {
                dir = dir.parentFile
            }
            checkNotNull(dir) { "Could not locate repo root (no settings.gradle.kts in parent chain)" }
        }

    @Test
    fun conventionPluginsAreIncludedAndAppliedToCoreModules() {
        val settings = File(root, "settings.gradle.kts").readText()
        assertContains(settings, "includeBuild(\"build-logic\")")

        val pluginDir = File(root, "build-logic/src/main/kotlin")
        assertTrue(File(pluginDir, "readylytics.kotlin-android-conventions.gradle.kts").exists())
        assertTrue(File(pluginDir, "readylytics.android-library-conventions.gradle.kts").exists())
        assertTrue(File(pluginDir, "readylytics.room-conventions.gradle.kts").exists())

        listOf("core/model", "core/scoring", "core/database", "core/healthconnect").forEach { module ->
            val buildFile = File(root, "$module/build.gradle.kts").readText()
            assertContains(buildFile, "id(\"readylytics.android-library-conventions\")")
        }

        val databaseBuildFile = File(root, "core/database/build.gradle.kts").readText()
        assertContains(databaseBuildFile, "id(\"readylytics.room-conventions\")")
    }
}
