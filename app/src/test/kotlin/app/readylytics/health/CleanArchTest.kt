package app.readylytics.health

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.imports
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.Test

class CleanArchTest {
    private val domainPackageGlobs =
        listOf(
            "app.readylytics.health.domain.migration..",
            "app.readylytics.health.domain.security..",
            "app.readylytics.health.domain.sync..",
            "app.readylytics.health.domain.user..",
            "app.readylytics.health.core.model.domain..",
            "app.readylytics.health.core.scoring.domain..",
            "app.readylytics.health.core.database.domain..",
            "app.readylytics.health.core.healthconnect.domain..",
            "app.readylytics.health.feature.dashboard.domain..",
        )

    private val dataLayerPackagePrefixes =
        listOf(
            "app.readylytics.health.data.backup.",
            "app.readylytics.health.data.crashreport.",
            "app.readylytics.health.data.device.",
            "app.readylytics.health.data.logcat.",
            "app.readylytics.health.data.migration.",
            "app.readylytics.health.data.preferences.",
            "app.readylytics.health.data.security.",
            "app.readylytics.health.data.util.",
            "app.readylytics.health.core.database.data.",
            "app.readylytics.health.core.healthconnect.data.",
            "app.readylytics.health.core.model.data.",
            "app.readylytics.health.core.databaseschema.data.",
        )

    private val dataPackageGlobs =
        dataLayerPackagePrefixes.map { it.dropLast(1) + ".." }

    private val allowedDataImports =
        setOf(
            "app.readylytics.health.core.model.data.preferences.UserPreferences",
            "app.readylytics.health.core.model.data.preferences.Gender",
            "app.readylytics.health.core.model.data.preferences.AppTheme",
            "app.readylytics.health.core.model.data.preferences.SettingsDefaults",
            "app.readylytics.health.core.model.data.preferences.PhysiologyProfile",
            "app.readylytics.health.core.model.data.preferences.UnitSystem",
            "app.readylytics.health.core.model.data.preferences.SyncPreference",
            "app.readylytics.health.core.model.data.preferences.BackgroundSyncInterval",
            "app.readylytics.health.core.model.data.preferences.FallbackThemeColor",
            "app.readylytics.health.core.model.data.preferences.BackupSchedule",
        )

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
                        // across module renames: catches both legacy and renamed DAO packages.
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
                .filter { file -> domainPackageGlobs.any { file.hasPackage(it) } }
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
                .filter { file ->
                    domainPackageGlobs.any { file.hasPackage(it) } &&
                        (file.path.contains("/src/main/") || file.path.contains("\\src\\main\\"))
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
        val dataRootAlternation =
            dataLayerPackagePrefixes
                .map { it.removePrefix("app.readylytics.health.").removeSuffix(".").replace(".", "\\.") }
                .sortedByDescending { it.length }
                .joinToString("|")
        val fqnRegex =
            Regex("""app\.readylytics\.health\.(?:$dataRootAlternation)\.[a-zA-Z0-9.]+""")

        val violations =
            Konsist
                .scopeFromProject()
                .files
                .filter { file ->
                    domainPackageGlobs.any { file.hasPackage(it) } &&
                        (file.path.contains("/src/main/") || file.path.contains("\\src\\main\\"))
                }.flatMap { file ->
                    val text = file.text
                    val matches = fqnRegex.findAll(text)
                    matches
                        .map { it.value }
                        .filter { ref ->
                            allowedDataImports.none { allowed ->
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
                .filter { file ->
                    (
                        domainPackageGlobs.any { file.hasPackage(it) } ||
                            dataPackageGlobs.any { file.hasPackage(it) }
                    ) &&
                        (file.path.contains("/src/main/") || file.path.contains("\\src\\main\\")) &&
                        !file.hasPackage("app.readylytics.health.feature..")
                }.flatMap { file ->
                    file.imports
                        .filter { import ->
                            import.name.startsWith("app.readylytics.health.feature.")
                        }.map { import -> "${file.name}: ${import.name}" }
                }

        org.junit.Assert.assertTrue(
            "Domain and Data layers must not import feature modules. Forbidden imports:\n${
                violations.joinToString("\n")
            }",
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
                        domainPackageGlobs.any { file.hasPackage(it) } ||
                            dataPackageGlobs.any { file.hasPackage(it) } ||
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
        val broadCatch = Regex("""catch \(\w+: (?:Exception|Throwable)\)""")
        val exceptionCatch = Regex("""catch \(\w+: Exception\)""")
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
                        text.contains("CancellationException") -> false
                        exceptionCatch.containsMatchIn(text) -> true
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

    @Test
    fun `no DAO type is injected outside core-database`() {
        val allowedFileNames =
            setOf(
                // R2-ARCH-002 follow-up: RestoreBatchLoader/BackupRecordDecoders inject the whole
                // HealthDatabase and pull DAOs off it directly throughout backup/restore. Fixing this
                // means refactoring the backup subsystem's data-access pattern, which is out of scope
                // for this architecture plan -- tracked separately, not part of R2-ARCH-002/DI-001.
                "RestoreBatchLoader.kt",
                "BackupRecordDecoders.kt",
            )
        val violations =
            Konsist
                .scopeFromProject()
                .files
                .filter { file ->
                    (file.path.contains("/src/main/") || file.path.contains("\\src\\main\\")) &&
                        !file.hasPackage("app.readylytics.health.core.database..") &&
                        !file.hasPackage("app.readylytics.health.core.databaseschema..") &&
                        file.nameWithExtension !in allowedFileNames
                }.flatMap { file ->
                    file.imports
                        .filter { it.name.contains(".data.local.dao.") }
                        .map { "${file.name}: ${it.name}" }
                }

        org.junit.Assert.assertTrue(
            "DAO types must only be injected inside core:database. Violations:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }

    @Test
    fun `UserPreferences is imported through the domain alias outside the data preferences package`() {
        val exemptFiles =
            setOf(
                "UserPreferencesMapper.kt",
                "UserPreferencesMapperExtensions.kt",
                "UserPreferencesSerializer.kt",
                "UserPreferencesSerializerExtensions.kt",
                "BackupPreferencesBuilder.kt",
                "RestorePreferencesExtensions.kt",
            )
        val violations =
            Konsist
                .scopeFromProject()
                .files
                .filter { file ->
                    (file.path.contains("/src/main/") || file.path.contains("\\src\\main\\")) &&
                        !file.hasPackage("app.readylytics.health.core.model.data.preferences..") &&
                        file.nameWithExtension !in exemptFiles
                }.flatMap { file ->
                    file.imports
                        .filter { it.name == "app.readylytics.health.core.model.data.preferences.UserPreferences" }
                        .map { file.name }
                }

        org.junit.Assert.assertTrue(
            "UserPreferences must be imported via the domain alias outside data/preferences. Violations:\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun `feature packages do not read Health Connect sample data directly`() {
        val bannedMembers =
            setOf(
                "readHeartRateSamples",
                "readHeartRateSamplesPaged",
                "readAllPagesStreaming",
                "readSleepSessions",
                "readExerciseSessions",
                "readHrvSamples",
            )
        val violations =
            Konsist
                .scopeFromProject()
                .files
                .filter { file ->
                    file.hasPackage("app.readylytics.health.feature..") &&
                        (file.path.contains("/src/main/") || file.path.contains("\\src\\main\\"))
                }.flatMap { file ->
                    bannedMembers
                        .filter { member -> Regex("""\.$member\s*\(""").containsMatchIn(file.text) }
                        .map { member -> "${file.name}: $member(...)" }
                }

        org.junit.Assert.assertTrue(
            "feature:* modules must read heart-rate/sleep/exercise/HRV data from Room, never Health " +
                "Connect directly. Violations:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }

    @Test
    fun `no ZoneId systemDefault in scoring database or feature ViewModels`() {
        val allowedFiles =
            setOf(
                "TimezoneProviderImpl.kt",
            )
        val violations =
            Konsist
                .scopeFromProject()
                .files
                .filter { file ->
                    (file.path.contains("/src/main/") || file.path.contains("\\src\\main\\")) &&
                        (
                            file.hasPackage("app.readylytics.health.core.scoring..") ||
                                file.hasPackage("app.readylytics.health.core.database..") ||
                                (
                                    file.hasPackage("app.readylytics.health.feature..") &&
                                        file.name.endsWith("ViewModel.kt")
                                )
                        ) &&
                        file.nameWithExtension !in allowedFiles
                }.flatMap { file ->
                    val matches = Regex("""ZoneId\.systemDefault\(\)""").findAll(file.text)
                    matches.map { "${file.name}: used ZoneId.systemDefault()" }.toList()
                }

        org.junit.Assert.assertTrue(
            "Date keys and scoring must use the stored scoring zone. Forbidden ZoneId.systemDefault() occurrences:\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun `feature packages do not import HealthConnectRepository`() {
        val violations =
            Konsist
                .scopeFromProject()
                .files
                .filter { file ->
                    file.hasPackage("app.readylytics.health.feature..") &&
                        (file.path.contains("/src/main/") || file.path.contains("\\src\\main\\"))
                }.flatMap { file ->
                    file.imports
                        .filter {
                            it.name == "app.readylytics.health.core.model.domain.repository.HealthConnectRepository"
                        }.map { "${file.name}: ${it.name}" }
                }

        org.junit.Assert.assertTrue(
            "feature:* modules must use HealthConnectPermissionChecker, not HealthConnectRepository. " +
                "Violations:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }
}
