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
        private const val BIRTHDAY_PICKER_PATH =
            "core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/settings/BirthdayDatePickerField.kt"
        val ALLOW_LIST =
            listOf(
                AllowedException(
                    relativePath = DATE_SWITCHER_PATH,
                    symbolSnippet = "today: LocalDate = LocalDate.now(),",
                    violation = "LocalDate.now()",
                    reason = "UI default parameter for today fallback in DateSwitcher",
                ),
                AllowedException(
                    relativePath = BIRTHDAY_PICKER_PATH,
                    symbolSnippet = "yearRange = 1900..LocalDate.now().year,",
                    violation = "LocalDate.now()",
                    reason = "UI date picker upper year bound in BirthdayDatePickerField",
                ),
                AllowedException(
                    relativePath = BIRTHDAY_PICKER_PATH,
                    symbolSnippet = "utcTimeMillis <= System.currentTimeMillis()",
                    violation = "System.currentTimeMillis()",
                    reason = "UI date picker upper selectable timestamp bound in BirthdayDatePickerField",
                ),
                AllowedException(
                    relativePath = BIRTHDAY_PICKER_PATH,
                    symbolSnippet =
                        "override fun isSelectableYear(year: Int): Boolean = year in 1900..LocalDate.now().year",
                    violation = "LocalDate.now()",
                    reason = "UI date picker selectable year bound in BirthdayDatePickerField",
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

    private fun collectScannedFiles(repoRoot: File): List<File> {
        val scannedFiles = mutableListOf<File>()

        // 1. core/*/src/main/kotlin
        val coreDir = File(repoRoot, "core")
        coreDir.listFiles()?.forEach { moduleDir ->
            val srcMainKotlin = File(moduleDir, "src/main/kotlin")
            if (srcMainKotlin.exists()) {
                scannedFiles.addAll(srcMainKotlin.walkTopDown().filter { it.isFile && it.extension == "kt" })
            }
        }

        // 2. app/.../workers
        val workersDir = File(repoRoot, "app/src/main/kotlin/app/readylytics/health/workers")
        if (workersDir.exists()) {
            scannedFiles.addAll(workersDir.walkTopDown().filter { it.isFile && it.extension == "kt" })
        }

        // 3. Preference classes (mappers and the Clock-taking preference facades)
        val appPrefix = "app/src/main/kotlin/app/readylytics/health"
        val prefDir = File(repoRoot, "$appPrefix/data/preferences")
        check(prefDir.exists()) { "Required scanned directory missing: $appPrefix/data/preferences" }
        scannedFiles.addAll(prefDir.walkTopDown().filter { it.isFile && it.extension == "kt" })
        val startupInitializer = File(repoRoot, "$appPrefix/DatabaseReadyStartupInitializer.kt")
        check(startupInitializer.exists()) { "Required scanned file missing: DatabaseReadyStartupInitializer.kt" }
        scannedFiles.add(startupInitializer)

        // 4. Identified feature VM/flow/loader files
        val dashPrefix = "feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard"
        val vitalsPrefix = "feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals"
        val workoutsPrefix = "feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts"
        val settingsPrefix = "feature/settings/src/main/kotlin/app/readylytics/health/feature/settings"
        val featurePaths =
            listOf(
                "$dashPrefix/DashboardFlowIntermediate.kt",
                "$dashPrefix/DashboardViewModel.kt",
                "$dashPrefix/DashboardClockPresentation.kt",
                "$vitalsPrefix/cardio/CardioFitnessDetailViewModel.kt",
                "$workoutsPrefix/WorkoutsViewModel.kt",
                "$workoutsPrefix/WorkoutsDataLoader.kt",
                "$settingsPrefix/PhysiologySettingsViewModel.kt",
            )
        for (fp in featurePaths) {
            val f = File(repoRoot, fp)
            check(f.exists()) { "Required scanned feature file missing: $fp" }
            scannedFiles.add(f)
        }

        return scannedFiles.distinct()
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
