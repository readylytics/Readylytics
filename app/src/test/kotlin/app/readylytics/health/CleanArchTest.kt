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
        // Value types that are domain-shaped but live under data.preferences for proto-schema reasons.
        // Only these specific types are allowed; data-layer impls (mappers, serializers, repos) are not.
        val allowedDataImports =
            setOf(
                "app.readylytics.health.data.preferences.UserPreferences",
                "app.readylytics.health.data.preferences.Gender",
                "app.readylytics.health.data.preferences.AppTheme",
                "app.readylytics.health.data.preferences.SettingsDefaults",
                "app.readylytics.health.data.preferences.PhysiologyProfile",
                "app.readylytics.health.data.preferences.UnitSystem",
                "app.readylytics.health.data.preferences.SyncPreference",
                "app.readylytics.health.data.preferences.BackgroundSyncInterval",
                "app.readylytics.health.data.preferences.FallbackThemeColor",
            )
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
                            import.name.startsWith("app.readylytics.health.data.") &&
                                import.name !in allowedDataImports
                        }.map { import -> "${file.name}: ${import.name}" }
                }

        org.junit.Assert.assertTrue(
            "Domain layer must not import data package. Forbidden imports:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }
}
