package app.readylytics.health.architecture

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class DatabaseProvisionArchitectureTest {
    private val root = sequenceOf(File("."), File("..")).first { File(it, "settings.gradle.kts").exists() }

    @Test
    fun `database provider body has no forbidden IO or blocking operations`() {
        val databaseModule =
            File(root, "core/database/src/main/kotlin/app/readylytics/health/core/database/di/DatabaseModule.kt")
        val content = databaseModule.readText()

        val functionStart = content.indexOf("fun provideDatabase(")
        val functionEnd = content.indexOf("return builder.build()", functionStart) + 25

        val body = content.substring(functionStart, functionEnd)

        val forbidden =
            listOf(
                "migrateIfNeeded",
                "inspect",
                "getDatabasePath",
                "File(",
                "getOrCreateDbKey",
                "System.loadLibrary",
            )

        for (term in forbidden) {
            assertFalse("contains $term", body.contains(term))
        }
    }
}
