package app.readylytics.health.architecture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppDomainOwnershipTest {
    private val root = sequenceOf(File("."), File("..")).first { File(it, "settings.gradle.kts").exists() }

    @Test
    fun `app domain packages are correctly modularized`() {
        val appPaths =
            listOf(
                "app/src/main/kotlin/app/readylytics/health/domain/validation/BirthdayDateRule.kt",
                "app/src/main/kotlin/app/readylytics/health/domain/calculation/HealthMetricsCalculator.kt",
                "app/src/main/kotlin/app/readylytics/health/domain/insights/InsightEngine.kt",
            )

        val coreModelPaths =
            listOf(
                "core/model/src/main/kotlin/app/readylytics/health/domain/validation/BirthdayDateRule.kt",
            )

        val coreScoringPaths =
            listOf(
                "core/scoring/src/main/kotlin/app/readylytics/health/domain/calculation/HealthMetricsCalculator.kt",
                "core/scoring/src/main/kotlin/app/readylytics/health/domain/insights/InsightEngine.kt",
            )

        appPaths.forEach { path ->
            assertFalse("app should not own $path", File(root, path).exists())
        }

        coreModelPaths.forEach { path ->
            assertTrue("core/model should own $path", File(root, path).exists())
        }

        coreScoringPaths.forEach { path ->
            assertTrue("core/scoring should own $path", File(root, path).exists())
        }

        // App domain contains security
        assertTrue(
            "app should keep DatabaseKeyRotator.kt",
            File(root, "app/src/main/kotlin/app/readylytics/health/domain/security/DatabaseKeyRotator.kt").isFile,
        )
        assertTrue(
            "app should keep DatabaseKeyOperations.kt",
            File(root, "app/src/main/kotlin/app/readylytics/health/domain/security/DatabaseKeyOperations.kt").isFile,
        )
    }
}
