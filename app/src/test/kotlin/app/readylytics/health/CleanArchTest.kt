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
                        // Matched as a contained segment (not startsWith) so this stays valid
                        // across module renames: it catches both the legacy
                        // `app.readylytics.health.data.local.dao` package and the current
                        // `app.readylytics.health.core.databaseschema.data.local.dao` package.
                        import.name.contains(".data.local.dao.")
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
        // Known roots of the shared data layer. Listed explicitly (rather than matching a bare
        // ".data." segment) because "data" is also used as an unrelated sub-package name elsewhere
        // (e.g. feature.settings.data), so a blind substring match would false-positive there.
        val dataLayerPackagePrefixes =
            listOf(
                "app.readylytics.health.data.",
                "app.readylytics.health.core.databaseschema.data.",
            )
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
                "app.readylytics.health.data.preferences.BackupSchedule",
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
                            dataLayerPackagePrefixes.any { prefix -> import.name.startsWith(prefix) } &&
                                import.name !in allowedDataImports
                        }.map { import -> "${file.name}: ${import.name}" }
                }

        org.junit.Assert.assertTrue(
            "Domain layer must not import data package. Forbidden imports:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }

    @Test
    fun `domain package does not reference data package via fully-qualified names`() {
        val allowedDataReferences =
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
                "app.readylytics.health.data.preferences.BackupSchedule",
            )

        val violations =
            Konsist
                .scopeFromProject()
                .files
                .filter {
                    it.hasPackage("app.readylytics.health.domain..") &&
                        (it.path.contains("/src/main/") || it.path.contains("\\src\\main\\"))
                }.flatMap { file ->
                    val text = file.text
                    // Alternation covers both the legacy `...health.data.` root and the
                    // `...health.core.databaseschema.data.` root the Room entities/DAOs now live
                    // under, so this stays valid across module renames.
                    val matches =
                        Regex("""app\.readylytics\.health\.(?:data|core\.databaseschema\.data)\.[a-zA-Z0-9.]+""")
                            .findAll(text)
                    matches
                        .map { it.value }
                        .filter { ref ->
                            allowedDataReferences.none { allowed ->
                                ref == allowed || ref.startsWith("$allowed.")
                            }
                        }.map { violation -> "${file.name}: referenced FQN $violation" }
                        .toList()
                }

        org.junit.Assert.assertTrue(
            "Domain layer must not use data layer FQNs. Violations:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }

    @Test
    fun `domain and data packages do not import feature package`() {
        val violations =
            Konsist
                .scopeFromProject()
                .files
                .filter {
                    (
                        it.hasPackage(
                            "app.readylytics.health.domain..",
                        ) ||
                            it.hasPackage("app.readylytics.health.data..") ||
                            it.hasPackage("app.readylytics.health.core.databaseschema.data..")
                    ) &&
                        (it.path.contains("/src/main/") || it.path.contains("\\src\\main\\")) &&
                        !it.path.contains("/feature/") &&
                        !it.path.contains("\\feature\\") &&
                        !it.hasPackage("app.readylytics.health.feature..")
                }.flatMap { file ->
                    file.imports
                        .filter { import ->
                            import.name.startsWith("app.readylytics.health.feature.")
                        }.map { import -> "${file.name}: ${import.name}" }
                }

        org.junit.Assert.assertTrue(
            "Domain and Data layers must not import feature modules. Forbidden imports:\n${violations.joinToString(
                "\n",
            )}",
            violations.isEmpty(),
        )
    }

    @Test
    fun `feature packages are only imported from allowed app shell composition points`() {
        val allowedImportsInApp =
            listOf(
                "app.readylytics.health.ui.navigation",
                "app.readylytics.health.ui.scaffold",
                "app.readylytics.health.di",
            )
        val violations =
            Konsist
                .scopeFromProject()
                .files
                .filter { file ->
                    (file.path.contains("/app/src/main/") || file.path.contains("\\app\\src\\main\\")) &&
                        !file.name.startsWith("MainActivity") &&
                        !file.name.startsWith("PrivacyRationaleActivity") &&
                        allowedImportsInApp.none { pkg -> file.hasPackage("$pkg..") }
                }.flatMap { file ->
                    file.imports
                        .filter { import ->
                            import.name.startsWith("app.readylytics.health.feature.")
                        }.map { import -> "${file.name}: ${import.name}" }
                }

        org.junit.Assert.assertTrue(
            "Feature imports are restricted in app shell. Forbidden imports:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }

    @Test
    fun `no hardcoded dispatchers outside of di packages`() {
        val violations =
            Konsist
                .scopeFromProject()
                .files
                .filter { file ->
                    val path = file.path
                    val isSource = (path.contains("/src/main/") || path.contains("\\src\\main\\"))
                    val isDi = (path.contains("/di/") || path.contains("\\di\\"))
                    val isDomainOrDataOrVm =
                        file.hasPackage("app.readylytics.health.domain..") ||
                            file.hasPackage("app.readylytics.health.data..") ||
                            file.hasPackage("app.readylytics.health.core.databaseschema.data..") ||
                            (file.hasPackage("app.readylytics.health.feature..") && file.name.endsWith("ViewModel.kt"))
                    isSource && !isDi && isDomainOrDataOrVm
                }.flatMap { file ->
                    val text = file.text
                    val matches = Regex("""Dispatchers\.(Default|IO)""").findAll(text)
                    matches.map { "${file.name}: hardcoded ${it.value}" }.toList()
                }

        val message =
            "Hardcoded dispatchers forbidden. Use @DefaultDispatcher or @IoDispatcher." +
                " Violations:\n${violations.joinToString("\n")}"
        org.junit.Assert.assertTrue(message, violations.isEmpty())
    }

    @Test
    fun `no doubled package segments exist`() {
        val violations =
            Konsist
                .scopeFromProject()
                .files
                .filter { file ->
                    val pkg = file.packagee?.name ?: ""
                    pkg.contains("dashboard.dashboard") || pkg.contains("circadian.circadian")
                }.map { "${it.name}: doubled package segment" }

        org.junit.Assert.assertTrue(
            "Doubled package segments are forbidden. Violations:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }

    @Test
    fun `suspend functions do not swallow CancellationException`() {
        // `catch (e: Throwable)` swallows cancellation exactly as completely as
        // `catch (e: Exception)`, so both are in scope.
        val broadCatch = Regex("""catch \(\w+: (?:Exception|Throwable)\)""")
        val exceptionCatch = Regex("""catch \(\w+: Exception\)""")
        // A `catch (t: Throwable) { … throw t }` re-raises whatever it caught, cancellation
        // included, so it is compliant without naming CancellationException. The backreference
        // requires the *same* variable be rethrown — `throw somethingElse` does not count.
        val rethrowsCaughtThrowable = Regex("""catch \((\w+): Throwable\)[\s\S]*?throw \1\b""")

        val violations =
            Konsist
                .scopeFromProject()
                .functions(includeNested = true, includeLocal = true)
                .filter {
                    (
                        it.containingFile.path.contains("/src/main/") ||
                            it.containingFile.path.contains("\\src\\main\\")
                    )
                }.filter { it.hasSuspendModifier }
                .filter { broadCatch.containsMatchIn(it.text) }
                .filter { function ->
                    val text = function.text
                    when {
                        // Explicit cancellation handling anywhere in the function.
                        text.contains("CancellationException") -> false
                        // A bare `catch (… : Exception)` can never be excused by a rethrow
                        // elsewhere; it must name CancellationException.
                        exceptionCatch.containsMatchIn(text) -> true
                        // Throwable-only: compliant iff every such catch rethrows what it caught.
                        else -> !rethrowsCaughtThrowable.containsMatchIn(text)
                    }
                }.map { "${it.containingFile.name}:${it.name}() swallows CancellationException" }

        org.junit.Assert.assertTrue(
            "Suspend functions must rethrow CancellationException before catching " +
                "Exception/Throwable (or rethrow the caught Throwable unchanged). " +
                "Violations:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }
}
