package app.readylytics.health.docs

import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Phase 0's deliverable is a recorded measurement set, not a passing build.
 *
 * This exists because of a failure that actually happened during Phase 0 execution:
 * `connectedBenchmarkAndroidTest` exited 0 with BUILD SUCCESSFUL while running ZERO tests, because
 * the instrumentation process was dying before any test reported. A green build is therefore not
 * evidence that anything was measured. This test fails if the dated Phase 0 section is missing a
 * metric, so a partial or empty benchmark run cannot be mistaken for a completed phase -- and fails
 * if the append clobbered the pre-existing July 2026 baselines, which `benchmark/BASELINE.md`'s own
 * append-only convention forbids.
 */
class Phase0BaselineCompletenessTest {
    private val baseline = readRepoFile("benchmark/BASELINE.md")

    private val requiredMetrics =
        listOf(
            "hr_upsert",
            "hr_reingest",
            "ingest_window",
            "workout_hr_fetch",
            "walk_forward_recompute",
            "changes_1k_per_record_write",
            "query_plan",
        )

    private val phase0Section: String
        get() = baseline.substringAfter(PHASE_0_HEADING, missingDelimiterValue = "")

    @Test
    fun `phase 0 section records every required metric`() {
        assertTrue(phase0Section.isNotBlank(), "benchmark/BASELINE.md has no '$PHASE_0_HEADING' section")
        val absent = requiredMetrics.filterNot { phase0Section.contains(it) }
        assertTrue(absent.isEmpty(), "Phase 0 section is missing metrics: $absent")
    }

    @Test
    fun `phase 0 section records all three scale points`() {
        assertTrue(phase0Section.isNotBlank(), "benchmark/BASELINE.md has no '$PHASE_0_HEADING' section")
        for (scale in listOf("250,000", "500,000", "1,000,000")) {
            assertTrue(phase0Section.contains(scale), "Phase 0 section does not record the $scale scale point")
        }
    }

    /**
     * The measurements come from a debuggable, non-AOT-compiled build, so they are valid for relative
     * before/after comparison and not as absolute release figures. If that caveat is ever dropped,
     * someone will quote these numbers as release performance.
     */
    @Test
    fun `phase 0 section states the measurement caveats`() {
        assertTrue(phase0Section.isNotBlank(), "benchmark/BASELINE.md has no '$PHASE_0_HEADING' section")
        assertTrue(
            phase0Section.contains("debuggable", ignoreCase = true),
            "Phase 0 section must record that these numbers come from a debuggable build",
        )
        assertTrue(
            phase0Section.contains("Deviations", ignoreCase = true),
            "Phase 0 section must carry a Deviations subsection, even if it says None",
        )
    }

    /** `benchmark/BASELINE.md` is append-only; earlier sections must survive. */
    @Test
    fun `earlier baseline sections survive the append`() {
        assertTrue(baseline.contains("## M2 Initial Baseline"), "the M2 baseline section was removed")
        assertTrue(
            baseline.contains("## F14 cold-start compilation comparison"),
            "the F14 cold-start section was removed",
        )
    }

    private fun readRepoFile(pathFromRepoRoot: String): String {
        val candidates =
            listOf(
                File(pathFromRepoRoot),
                File("../$pathFromRepoRoot"),
                File("../../$pathFromRepoRoot"),
            )
        val file = candidates.firstOrNull { it.exists() }
        assertTrue(file != null, "could not locate $pathFromRepoRoot from working dir ${File(".").absolutePath}")
        return file.readText()
    }

    private companion object {
        const val PHASE_0_HEADING = "## Phase 0 Baseline"
    }
}
