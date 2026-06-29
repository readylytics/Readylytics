package app.readylytics.health.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FeatureResourceOwnershipTest {
    private val root = sequenceOf(File("."), File("..")).first { File(it, "settings.gradle.kts").exists() }
    private val modulePaths =
        listOf(
            "app",
            "core/designsystem",
            "core/ui",
            "feature/about",
            "feature/insights",
            "feature/sleep",
            "feature/workouts",
            "feature/vitals",
            "feature/dashboard",
            "feature/settings",
            "feature/onboarding",
        )

    @Test
    fun `string resources have one owning module`() {
        val declaration = Regex("""<string\s+name="([^"]+)"""")
        val owners = mutableMapOf<String, MutableSet<String>>()
        modulePaths.forEach { module ->
            val resDir = File(root, "$module/src/main/res")
            if (resDir.exists()) {
                resDir.walkTopDown().filter { it.isFile && it.extension == "xml" }.forEach { xml ->
                    declaration.findAll(xml.readText()).forEach { match ->
                        owners.getOrPut(match.groupValues[1]) { linkedSetOf() }.add(module)
                    }
                }
            }
        }
        val duplicates = owners.filterValues { it.size > 1 }
        assertTrue("String resources must have one owner: $duplicates", duplicates.isEmpty())
    }
}
