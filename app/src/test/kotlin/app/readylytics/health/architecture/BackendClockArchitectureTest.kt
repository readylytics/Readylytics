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
        private const val PERF_MONITOR_PATH =
            "core/model/src/main/kotlin/app/readylytics/health/core/model/domain/util/PerformanceMonitor.kt"

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
                AllowedException(
                    relativePath = PERF_MONITOR_PATH,
                    symbolSnippet = "val timestamp: Long = System.currentTimeMillis(),",
                    violation = "System.currentTimeMillis()",
                    reason = "Diagnostic timestamp default in PerformanceMonitor",
                ),
                AllowedException(
                    relativePath =
                        "core/database/src/main/kotlin/app/readylytics/health/" +
                            "core/database/domain/sync/DailyRecomputeSupport.kt",
                    symbolSnippet = "private val clock: Clock = Clock.systemDefaultZone(),",
                    violation = "Clock.systemDefaultZone()",
                    reason = "Hilt injects the Clock binding; retained existing test-construction compatibility",
                    owner = "class DailyRecomputeSupport",
                ),
                AllowedException(
                    relativePath =
                        "core/database/src/main/kotlin/app/readylytics/health/" +
                            "core/database/data/local/RoomHealthIngestionStore.kt",
                    symbolSnippet = "private val clock: Clock = Clock.systemDefaultZone(),",
                    violation = "Clock.systemDefaultZone()",
                    reason = "Hilt supplies Clock and the SourcePayloadWriter fallback forwards it",
                    owner = "class RoomHealthIngestionStore",
                ),
                AllowedException(
                    relativePath =
                        "core/database/src/main/kotlin/app/readylytics/health/" +
                            "core/database/data/local/RoomHealthChangeIngestionStore.kt",
                    symbolSnippet = "private val clock: Clock = Clock.systemDefaultZone(),",
                    violation = "Clock.systemDefaultZone()",
                    reason = "Hilt injects the Clock binding for deletion-journal dates",
                    owner = "class RoomHealthChangeIngestionStore",
                ),
                AllowedException(
                    relativePath =
                        "core/database/src/main/kotlin/app/readylytics/health/" +
                            "core/database/data/local/RoomScanStagingStore.kt",
                    symbolSnippet = "private val clock: Clock = Clock.systemUTC(),",
                    violation = "Clock.systemUTC()",
                    reason = "Hilt injects Clock for scan timestamps",
                    owner = "class RoomScanStagingStore",
                ),
                AllowedException(
                    relativePath =
                        "core/database/src/main/kotlin/app/readylytics/health/" +
                            "core/database/data/local/SourcePayloadWriter.kt",
                    symbolSnippet = "private val clock: Clock = Clock.systemDefaultZone(),",
                    violation = "Clock.systemDefaultZone()",
                    reason = "Hilt supplies Clock; RoomHealthIngestionStore also explicitly forwards its Clock",
                    owner = "class SourcePayloadWriter",
                ),
                AllowedException(
                    relativePath =
                        "core/database/src/main/kotlin/app/readylytics/health/" +
                            "core/database/data/repository/ScoringRepositoryImpl.kt",
                    symbolSnippet = "private val clock: Clock = Clock.systemDefaultZone(),",
                    violation = "Clock.systemDefaultZone()",
                    reason = "Hilt injects Clock into single-day scoring context",
                    owner = "class ScoringRepositoryImpl",
                ),
                AllowedException(
                    relativePath =
                        "core/healthconnect/src/main/kotlin/app/readylytics/health/" +
                            "core/healthconnect/domain/sync/HistoricalIngestPhase.kt",
                    symbolSnippet = "private val clock: Clock = Clock.systemDefaultZone(),",
                    violation = "Clock.systemDefaultZone()",
                    reason = "Hilt supplies Clock; ResyncRangeUseCase fallback also forwards Clock",
                    owner = "class HistoricalIngestPhase",
                ),
                AllowedException(
                    relativePath =
                        "core/healthconnect/src/main/kotlin/app/readylytics/health/" +
                            "core/healthconnect/domain/sync/HistoricalPrunePhase.kt",
                    symbolSnippet = "private val clock: Clock = Clock.systemDefaultZone(),",
                    violation = "Clock.systemDefaultZone()",
                    reason = "Hilt supplies Clock; ResyncRangeUseCase fallback also forwards Clock",
                    owner = "class HistoricalPrunePhase",
                ),
                AllowedException(
                    relativePath =
                        "core/healthconnect/src/main/kotlin/app/readylytics/health/" +
                            "core/healthconnect/domain/sync/HistoricalRecomputePhase.kt",
                    symbolSnippet = "private val clock: Clock = Clock.systemDefaultZone(),",
                    violation = "Clock.systemDefaultZone()",
                    reason = "Hilt supplies Clock; ResyncRangeUseCase fallback also forwards Clock",
                    owner = "class HistoricalRecomputePhase",
                ),
                AllowedException(
                    relativePath =
                        "core/healthconnect/src/main/kotlin/app/readylytics/health/" +
                            "core/healthconnect/domain/sync/ResyncRangeUseCase.kt",
                    symbolSnippet = "private val clock: Clock = Clock.systemDefaultZone(),",
                    violation = "Clock.systemDefaultZone()",
                    reason = "Hilt injects Clock and passes it to all fallback phases",
                    owner = "class ResyncRangeUseCase",
                ),
                AllowedException(
                    relativePath =
                        "core/healthconnect/src/main/kotlin/app/readylytics/health/" +
                            "core/healthconnect/data/healthconnect/HealthChangeSynchronizerImpl.kt",
                    symbolSnippet = "private val clock: Clock = Clock.systemDefaultZone(),",
                    violation = "Clock.systemDefaultZone()",
                    reason = "Hilt injects Clock for changes synchronization timestamps",
                    owner = "class HealthChangeSynchronizerImpl",
                ),
                AllowedException(
                    relativePath =
                        "core/model/src/main/kotlin/app/readylytics/health/" +
                            "core/model/domain/service/DateRangeService.kt",
                    symbolSnippet = "private val clock: Clock = Clock.systemDefaultZone(),",
                    violation = "Clock.systemDefaultZone()",
                    reason =
                        "Existing pure helper has no production instance or backend caller; " +
                            "compatibility API remains outside DI-101",
                    owner = "class DateRangeService",
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

    @Test
    fun `scanned backend and feature sources do not contain unclocked time calls`() {
        val repoRoot = resolveRepoRoot()
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

        // 3. Preference mapper files
        val prefPrefix = "app/src/main/kotlin/app/readylytics/health/data/preferences"
        val mapper1 = File(repoRoot, "$prefPrefix/UserPreferencesMapper.kt")
        val mapper2 = File(repoRoot, "$prefPrefix/UserPreferencesMapperExtensions.kt")
        if (mapper1.exists()) scannedFiles.add(mapper1)
        if (mapper2.exists()) scannedFiles.add(mapper2)

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

        val allViolations = mutableListOf<String>()

        for (file in scannedFiles.distinct()) {
            val relPath = file.relativeTo(repoRoot).path
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
