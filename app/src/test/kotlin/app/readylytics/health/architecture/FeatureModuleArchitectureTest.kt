package app.readylytics.health.architecture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FeatureModuleArchitectureTest {
    private val root = sequenceOf(File("."), File("..")).first { File(it, "settings.gradle.kts").exists() }

    @Test
    fun `declared feature modules exist and are included`() {
        val settings = File(root, "settings.gradle.kts").readText()
        expectedFeatureModules.forEach { name ->
            assertTrue("Missing :feature:$name", File(root, "feature/$name/build.gradle.kts").isFile)
            assertTrue("Settings omit :feature:$name", settings.contains("include(\":feature:$name\")"))
        }
    }

    @Test
    fun `existing feature modules have no forbidden project dependencies`() {
        val forbidden = listOf("project(\":app\")", "project(\":core:database\")", "project(\":core:healthconnect\")")
        featureDirectories().forEach { module ->
            val buildScript = File(module, "build.gradle.kts").readText()
            forbidden.forEach { dependency ->
                assertFalse("${module.name}: $dependency", buildScript.contains(dependency))
            }
            featureDirectories().map { it.name }.forEach { peer ->
                assertFalse(
                    "${module.name} depends on feature $peer",
                    buildScript.contains("project(\":feature:$peer\")"),
                )
            }
        }
    }

    @Test
    fun `existing feature sources have no forbidden imports`() {
        val forbidden =
            listOf(
                "app.readylytics.health.R",
                "app.readylytics.health.data.",
                "app.readylytics.health.workers.",
                "androidx.room.",
                "androidx.health.connect.",
                "androidx.work.",
            )
        featureDirectories().forEach { module ->
            File(module, "src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.forEach { source ->
                source.readLines().forEachIndexed { index, line ->
                    if (line.startsWith("import ")) {
                        forbidden.forEach { prefix ->
                            if (prefix == "app.readylytics.health.data." &&
                                line.contains("app.readylytics.health.data.preferences.")
                            ) {
                                // allowed preferences package imports
                            } else {
                                assertFalse("${source.relativeTo(root)}:${index + 1}: $prefix", line.contains(prefix))
                            }
                        }
                        if (line.contains("app.readylytics.health.feature.")) {
                            assertTrue(
                                "${source.relativeTo(root)}:${index + 1}: cross-feature import",
                                line.contains("app.readylytics.health.feature.${module.name}."),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun featureDirectories(): List<File> =
        File(
            root,
            "feature",
        ).listFiles().orEmpty().filter { File(it, "build.gradle.kts").isFile }.sortedBy(File::getName)
}
