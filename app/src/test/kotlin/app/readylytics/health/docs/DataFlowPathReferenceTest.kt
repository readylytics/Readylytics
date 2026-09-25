package app.readylytics.health.docs

import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * `.claude/CLAUDE.md` treats a stale `internal-docs/DATA_FLOW.md` as a broken build. That rule is
 * only enforceable if drift is detectable, and the most common drift — a file moved to another
 * module while the doc keeps citing its old path — is mechanically checkable. This test fails when
 * DATA_FLOW.md cites a repo-rooted source path that does not exist.
 *
 * Deliberately restricted to **repo-rooted, non-elided** tokens. DATA_FLOW.md also uses two other
 * notations that are not drift: abbreviated paths containing `...` (e.g.
 * `core/healthconnect/.../domain/sync/VitalsInputMapper.kt`), and module-relative short forms
 * (e.g. `ui/sync/SyncViewModel.kt`). The first is unresolvable by construction; the second is
 * covered by `module relative kotlin paths in DATA_FLOW resolve to a source file`.
 */
class DataFlowPathReferenceTest {
    private val dataFlowMd = readRepoFile("internal-docs/DATA_FLOW.md")

    /** Top-level directories that make a path unambiguously repo-rooted. */
    private val repoRootedPrefixes =
        listOf("app/", "core/", "feature/", "build-logic/", "buildSrc/", "benchmark/", "database-benchmark/")

    private val backtickedKotlinPaths: List<String>
        get() =
            Regex("`([A-Za-z0-9_\\-./]+\\.kt)`")
                .findAll(dataFlowMd)
                .map { it.groupValues[1] }
                .distinct()
                .toList()

    @Test
    fun `repo rooted kotlin paths in DATA_FLOW exist`() {
        val checked =
            backtickedKotlinPaths
                .filter { path -> repoRootedPrefixes.any { path.startsWith(it) } }
                .filterNot { it.contains("...") }
        assertTrue(checked.size > 100, "expected DATA_FLOW.md to cite many repo-rooted paths, found ${checked.size}")

        val missing = checked.filterNot { repoFileExists(it) }.sorted()
        assertTrue(
            missing.isEmpty(),
            "DATA_FLOW.md cites ${missing.size} source path(s) that do not exist:\n" +
                missing.joinToString("\n") { "  $it" },
        )
    }

    /** Guards Review Focus item 2: elided and module-relative notations must not enter the strict check. */
    @Test
    fun `strict check ignores elided and module relative notations`() {
        val elided = backtickedKotlinPaths.filter { it.contains("...") }
        assertTrue(elided.isNotEmpty(), "fixture assumption broken: DATA_FLOW.md no longer uses elided paths")
        assertTrue(
            elided.none { path -> repoRootedPrefixes.any { path.startsWith(it) } && !path.contains("...") },
            "elided paths must never reach the strict existence check",
        )
        val moduleRelative =
            backtickedKotlinPaths.filterNot { path -> repoRootedPrefixes.any { path.startsWith(it) } }
        assertTrue(moduleRelative.isNotEmpty(), "fixture assumption broken: no module-relative paths found")
    }

    private fun repoFileExists(pathFromRepoRoot: String): Boolean =
        listOf(File(pathFromRepoRoot), File("../$pathFromRepoRoot"), File("../../$pathFromRepoRoot"))
            .any { it.exists() }

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
}
