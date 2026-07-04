package app.readylytics.health.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class CompleteFeatureTopologyTest {
    private val root = sequenceOf(File("."), File("..")).first { File(it, "settings.gradle.kts").exists() }

    @Test
    fun `all eight features exist and app owns no feature presentation package`() {
        val actual =
            File(root, "feature")
                .listFiles()
                .orEmpty()
                .filter {
                    File(it, "build.gradle.kts").isFile
                }.map { it.name }
                .toSet()
        assertEquals(
            setOf("about", "insights", "sleep", "workouts", "vitals", "dashboard", "settings", "onboarding"),
            actual,
        )
        val appUi = File(root, "app/src/main/kotlin/app/readylytics/health/ui")
        listOf(
            "about",
            "insights",
            "sleep",
            "workouts",
            "vitals",
            "heartrate",
            "bloodpressure",
            "weight",
            "bodyfat",
            "steps",
            "dashboard",
            "settings",
            "onboarding",
        ).forEach {
            assertFalse("App still owns ui/$it", File(appUi, it).exists())
        }
    }
}
