package app.readylytics.health.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DiModuleCohesionTest {
    private val root = sequenceOf(File("."), File("..")).first { File(it, "settings.gradle.kts").exists() }
    private val diDir =
        File(root, "core/database/src/main/kotlin/app/readylytics/health/core/database/di")

    @Test
    fun `core database DI modules are grouped by concern not split by function count`() {
        val supplementModules = diDir.listFiles().orEmpty().filter { it.name.contains("Supplement") }

        assertTrue("Supplement modules found: ${supplementModules.map { it.name }}", supplementModules.isEmpty())
    }

    @Test
    fun `no core database DI module declares more than ten functions`() {
        val oversized =
            diDir
                .listFiles()
                .orEmpty()
                .filter { it.extension == "kt" }
                .associate { it.name to Regex("""\n\s+(?:abstract\s+)?fun\s""").findAll(it.readText()).count() }
                .filterValues { it > 10 }

        assertTrue("Modules with more than ten functions: $oversized", oversized.isEmpty())
    }
}
