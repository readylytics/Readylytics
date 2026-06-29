package app.readylytics.health.build

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CoverageAggregationPresenceTest {
    private val root = sequenceOf(File("."), File("..")).first { File(it, "settings.gradle.kts").exists() }

    @Test
    fun `root build and CI files configure aggregate coverage for all features`() {
        val rootBuild = File(root, "build.gradle.kts")
        assertTrue("root build.gradle.kts file should exist", rootBuild.isFile)
        val buildText = rootBuild.readText()

        assertTrue("root build should register jacocoTestReport", buildText.contains("jacocoTestReport"))
        assertTrue(
            "root build should register jacocoCoverageVerification",
            buildText.contains("jacocoCoverageVerification"),
        )

        val features = listOf("about", "insights", "sleep", "workouts", "vitals", "dashboard", "settings", "onboarding")
        features.forEach { feature ->
            assertTrue(
                "root build should include :feature:$feature in coverage projects",
                buildText.contains("\":feature:$feature\""),
            )
        }

        val ciFile = File(root, ".github/workflows/ci.yml")
        assertTrue("ci.yml file should exist", ciFile.isFile)
        val ciText = ciFile.readText()

        assertTrue(
            "CI should upload root build reports directory",
            ciText.contains("path: build/reports/jacoco/jacocoTestReport/") ||
                ciText.contains("path: ./build/reports/jacoco/jacocoTestReport/"),
        )
    }
}
