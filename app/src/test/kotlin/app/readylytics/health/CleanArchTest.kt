package app.readylytics.health

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.imports
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.Test

class CleanArchTest {
    @Test
    fun `ui package does not import room daos`() {
        Konsist
            .scopeFromProject()
            .files
            .filter { it.hasPackage("app.readylytics.health.ui..") }
            .assertTrue { file ->
                val hasDaoImport =
                    file.imports.any { import ->
                        import.name.startsWith("app.readylytics.health.data.local.dao")
                    }
                !hasDaoImport
            }
    }

    @Test
    fun `domain package does not import Android Compose Health Connect or app util APIs`() {
        val forbiddenPrefixes =
            listOf(
                "android.",
                "androidx.compose.",
                "androidx.health.",
                "app.readylytics.health.util.",
                "app.readylytics.health.BuildConfig",
            )
        val violations =
            Konsist
                .scopeFromProject()
                .files
                .filter { it.hasPackage("app.readylytics.health.domain..") }
                .flatMap { file ->
                    file.imports
                        .filter { import ->
                            forbiddenPrefixes.any { prefix -> import.name.startsWith(prefix) }
                        }.map { import -> "${file.name}: ${import.name}" }
                }

        org.junit.Assert.assertTrue(
            "Domain layer must stay pure Kotlin. Forbidden imports:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }

    @Test
    fun `domain package does not import data package`() {
        val violations =
            Konsist
                .scopeFromProject()
                .files
                .filter {
                    it.hasPackage("app.readylytics.health.domain..") &&
                        (it.path.contains("/src/main/") || it.path.contains("\\src\\main\\"))
                }.flatMap { file ->
                    file.imports
                        .filter { import ->
                            import.name.startsWith("app.readylytics.health.data.")
                        }.map { import -> "${file.name}: ${import.name}" }
                }

        org.junit.Assert.assertTrue(
            "Domain layer must not import data package. Forbidden imports:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }
}
