package app.readylytics.health.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BackendClockArchitectureTest {
    data class AllowedException(
        val relativePath: String,
        val symbolSnippet: String,
        val violation: String,
        val reason: String,
        val owner: String? = null,
    )

    companion object {
        private const val DATE_SWITCHER_PATH =
            "core/ui/src/main/kotlin/app/readylytics/health/core/ui/dashboard/DateSwitcher.kt"
        val ALLOW_LIST =
            listOf(
                AllowedException(
                    relativePath = DATE_SWITCHER_PATH,
                    symbolSnippet = "today: LocalDate = LocalDate.now(),",
                    violation = "LocalDate.now()",
                    reason = "UI default parameter for today fallback in DateSwitcher",
                ),
            )
    }

    private fun resolveRepoRoot(): File {
        var dir: File? = File(".").canonicalFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        return dir ?: error("Could not find repository root containing settings.gradle.kts")
    }

    @Test
    fun `all allow-listed symbols exist in their source files`() {
        val repoRoot = resolveRepoRoot()
        for (allowed in ALLOW_LIST) {
            val file = File(repoRoot, allowed.relativePath)
            assertTrue("Allow-listed file ${allowed.relativePath} must exist", file.exists())
            val content = file.readText()
            assertTrue(
                "Allow-listed symbol snippet '${allowed.symbolSnippet}' must exist in ${allowed.relativePath}",
                content.contains(allowed.symbolSnippet),
            )
            assertEquals(
                "Exception must identify exactly one occurrence",
                1,
                Regex(Regex.escape(allowed.symbolSnippet)).findAll(content).count(),
            )
            assertTrue("Named owner must exist", allowed.owner == null || content.contains(allowed.owner))
            allowed.owner?.let { owner ->
                val ownerStart = content.indexOf(owner)
                val constructorEnd = content.indexOf('{', ownerStart)
                assertTrue(
                    "Compatibility exception must be in the named constructor",
                    content.indexOf(allowed.symbolSnippet) in ownerStart until constructorEnd,
                )
            }
            assertEquals(
                listOf(allowed.violation),
                ClockCallScanner.violations(allowed.symbolSnippet),
            )
        }
    }

    private fun collectScannedFiles(repoRoot: File): List<File> =
        (
            collectCoreAndAppDirectories(repoRoot) +
                collectClockedAppFiles(repoRoot) +
                collectFeatureFiles(repoRoot) +
                collectBirthdayUiFiles(repoRoot)
        ).distinct()

    private fun kotlinFilesUnder(dir: File): List<File> =
        dir
            .walkTopDown()
            .filter {
                it.isFile && it.extension == "kt"
            }.toList()

    private fun requireFiles(
        repoRoot: File,
        relativePaths: List<String>,
    ): List<File> =
        relativePaths.map { relative ->
            File(repoRoot, relative).also { check(it.exists()) { "Required scanned file missing: $relative" } }
        }

    private fun collectCoreAndAppDirectories(repoRoot: File): List<File> {
        val appPrefix = "app/src/main/kotlin/app/readylytics/health"
        val prefDir = File(repoRoot, "$appPrefix/data/preferences")
        check(prefDir.exists()) { "Required scanned directory missing: $appPrefix/data/preferences" }
        val coreSources =
            File(repoRoot, "core").listFiles().orEmpty().flatMap { moduleDir ->
                File(moduleDir, "src/main/kotlin").takeIf { it.exists() }?.let(::kotlinFilesUnder).orEmpty()
            }
        val workers = File(repoRoot, "$appPrefix/workers").takeIf { it.exists() }?.let(::kotlinFilesUnder).orEmpty()
        return coreSources +
            workers +
            kotlinFilesUnder(prefDir) +
            requireFiles(repoRoot, listOf("$appPrefix/DatabaseReadyStartupInitializer.kt"))
    }

    // Backup/restore, key rotation and crash-report classes that take an injected Clock.
    private fun collectClockedAppFiles(repoRoot: File): List<File> {
        val appPrefix = "app/src/main/kotlin/app/readylytics/health"
        return requireFiles(
            repoRoot,
            listOf(
                "data/backup/LocalBackupManager.kt",
                "data/backup/LocalRestoreManager.kt",
                "data/backup/BackupRotationService.kt",
                "data/backup/RestoreRecommendationCoverageChecker.kt",
                "domain/security/DatabaseKeyRotator.kt",
                "crashreport/CrashReportHandler.kt",
                "crashreport/CrashReportFileExport.kt",
                "crashreport/CrashReportShareIntent.kt",
                "data/backup/BackupSnapshotExporter.kt",
                "ui/sync/SyncViewModel.kt",
            ).map { "$appPrefix/$it" },
        )
    }

    // Birthday UI takes its limits from BirthdayBounds (SettingsValidators.birthdayBounds(clock)), never wall time.
    private fun collectBirthdayUiFiles(repoRoot: File): List<File> =
        requireFiles(
            repoRoot,
            listOf(
                "core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/settings/BirthdayDatePickerField.kt",
                "feature/onboarding/src/main/kotlin/app/readylytics/health/feature/onboarding/OnboardingScreen.kt",
            ),
        )

    // Identified feature VM/flow/loader files.
    private fun collectFeatureFiles(repoRoot: File): List<File> {
        val dashPrefix = "feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard"
        val vitalsPrefix = "feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals"
        val workoutsPrefix = "feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts"
        val settingsPrefix = "feature/settings/src/main/kotlin/app/readylytics/health/feature/settings"
        val onboardingPrefix = "feature/onboarding/src/main/kotlin/app/readylytics/health/feature/onboarding"
        return requireFiles(
            repoRoot,
            listOf(
                "$settingsPrefix/LocalBackupViewModel.kt",
                "$dashPrefix/DashboardFlowIntermediate.kt",
                "$dashPrefix/DashboardViewModel.kt",
                "$dashPrefix/DashboardClockPresentation.kt",
                "$vitalsPrefix/cardio/CardioFitnessDetailViewModel.kt",
                "$workoutsPrefix/WorkoutsViewModel.kt",
                "$workoutsPrefix/WorkoutsDataLoader.kt",
                "$settingsPrefix/PhysiologySettingsViewModel.kt",
                "$onboardingPrefix/OnboardingViewModel.kt",
                "$vitalsPrefix/overview/VitalsViewModel.kt",
                "$vitalsPrefix/heartrate/HeartRateUiModels.kt",
                "$workoutsPrefix/WorkoutsStateFactory.kt",
            ),
        )
    }

    @Test
    fun `scanned backend and feature sources do not contain unclocked time calls`() {
        val repoRoot = resolveRepoRoot()
        val scannedFiles = collectScannedFiles(repoRoot)

        val allViolations = mutableListOf<String>()

        for (file in scannedFiles) {
            val relPath = file.relativeTo(repoRoot).invariantSeparatorsPath
            var content = file.readText()

            // Mask allow-listed symbol snippets in allow-listed files
            val exceptionsForFile = ALLOW_LIST.filter { it.relativePath == relPath }
            for (exc in exceptionsForFile) {
                if (content.contains(exc.symbolSnippet)) {
                    // Replace snippet with blank spaces of the same length to preserve offsets
                    content = content.replaceFirst(exc.symbolSnippet, " ".repeat(exc.symbolSnippet.length))
                }
            }

            val violations = ClockCallScanner.violations(content)
            if (violations.isNotEmpty()) {
                allViolations.add("$relPath: $violations")
            }
        }

        assertTrue(
            "Found unclocked time calls in scanned files:\n" + allViolations.joinToString("\n"),
            allViolations.isEmpty(),
        )
    }
}
