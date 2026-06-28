package app.readylytics.health.architecture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DashboardFeatureCompositionTest {
    private val root = sequenceOf(File("."), File("..")).first { File(it, "settings.gradle.kts").exists() }

    @Test
    fun `dashboard feature exposes insight callback and slot without insights dependency`() {
        val source =
            File(
                root,
                "feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardScreen.kt",
            )
        assertTrue("Dashboard feature route missing", source.isFile)

        val text = source.readText()
        assertTrue(
            "DashboardRoute must expose onOpenInsight callback",
            text.contains("onOpenInsight: (InsightParams) -> Unit"),
        )
        assertTrue(
            "Dashboard route must accept insight detail slot",
            text.contains("insightDetail: @Composable (() -> Unit)? = null"),
        )
        assertFalse("Dashboard must not import feature insights", text.contains("feature.insights"))
    }
}
