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
                "app.readylytics.health.core.database.data.",
                "app.readylytics.health.core.healthconnect.data.",
                "app.readylytics.health.core.model.data.",
                "app.readylytics.health.core.databaseschema.data.",
                "app.readylytics.health.workers.",
                "app.readylytics.health.core.model.workers.",
                "androidx.room.",
                "androidx.health.connect.",
                "androidx.work.",
            )
        featureDirectories().forEach { module ->
            File(module, "src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.forEach { source ->
                source.readLines().forEachIndexed { index, line ->
                    if (line.startsWith("import ")) {
                        forbidden.forEach { prefix ->
                            if (!isAllowedImport(prefix, source, line)) {
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

    private fun isAllowedImport(
        prefix: String,
        source: File,
        line: String,
    ): Boolean =
        when {
            !line.contains(prefix) -> true
            isAllowedPreferencesImport(prefix, line) -> true
            else -> isAllowedHealthConnectImport(prefix, source, line)
        }

    private fun isAllowedPreferencesImport(
        prefix: String,
        line: String,
    ): Boolean =
        prefix.endsWith(".data.") &&
            line.contains(prefix.replace(".data.", ".data.preferences."))

    private fun isAllowedHealthConnectImport(
        prefix: String,
        source: File,
        line: String,
    ): Boolean =
        prefix == "androidx.health.connect." &&
            source.name in allowedHealthConnectSources &&
            allowedHealthConnectApis.any { line.contains(it) }

    private fun featureDirectories(): List<File> =
        File(
            root,
            "feature",
        ).listFiles().orEmpty().filter { File(it, "build.gradle.kts").isFile }.sortedBy(File::getName)

    private companion object {
        val allowedHealthConnectSources =
            setOf(
                "DataSettings.kt",
                "OnboardingRoute.kt",
                "PermissionBullets.kt",
            )

        val allowedHealthConnectApis =
            listOf(
                "androidx.health.connect.client.PermissionController",
                "androidx.health.connect.client.permission.HealthPermission",
                "androidx.health.connect.client.HealthConnectClient",
                "androidx.health.connect.client.records.",
            )
    }
}
