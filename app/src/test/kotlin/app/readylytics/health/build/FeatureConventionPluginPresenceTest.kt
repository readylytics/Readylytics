package app.readylytics.health.build

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FeatureConventionPluginPresenceTest {
    private val root = sequenceOf(File("."), File("..")).first { File(it, "settings.gradle.kts").exists() }

    @Test
    fun `compose and feature conventions exist with required plugins`() {
        val compose = File(root, "build-logic/src/main/kotlin/readylytics.compose-library-conventions.gradle.kts")
        val feature = File(root, "build-logic/src/main/kotlin/readylytics.compose-feature-conventions.gradle.kts")
        assertTrue("compose convention file should exist", compose.isFile)
        assertTrue("feature convention file should exist", feature.isFile)
        val composeText = compose.readText()
        val featureText = feature.readText()
        assertTrue("compose should contain compose plugin", composeText.contains("org.jetbrains.kotlin.plugin.compose"))
        assertTrue("feature should contain hilt plugin", featureText.contains("com.google.dagger.hilt.android"))
        assertTrue("feature should contain core:ui project", featureText.contains("project(\":core:ui\")"))
        assertTrue("feature should contain core:model project", featureText.contains("project(\":core:model\")"))
    }
}
