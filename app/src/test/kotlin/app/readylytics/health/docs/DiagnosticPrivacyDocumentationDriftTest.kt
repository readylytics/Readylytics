package app.readylytics.health.docs

import app.readylytics.health.core.model.domain.sync.ResyncPhase
import app.readylytics.health.core.model.domain.util.CountBucket
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SEC-101 / OD-1: docs/privacy.md describes exactly what a diagnostic entry can contain. */
class DiagnosticPrivacyDocumentationDriftTest {
    private val privacyMd = readRepoFile("docs/privacy.md")

    @Test
    fun `privacy policy lists every diagnostic field`() {
        assertTrue(privacyMd.contains("### What a diagnostic log entry contains"))
        ResyncPhase.entries.forEach { phase ->
            val step = phase.name.lowercase()
            assertTrue(privacyMd.contains(step), "privacy.md must name the '$step' step")
        }
        assertEquals(4, CountBucket.entries.size, "update privacy.md's count ranges when CountBucket changes")
        listOf("none", "1–10", "11–100", "more than 100", "never the calendar date").forEach { phrase ->
            assertTrue(privacyMd.contains(phrase), "privacy.md must contain '$phrase'")
        }
    }

    private fun readRepoFile(pathFromRepoRoot: String): String =
        listOf(File(pathFromRepoRoot), File("../$pathFromRepoRoot"), File("../../$pathFromRepoRoot"))
            .firstOrNull { it.exists() }
            ?.readText()
            ?: error("could not locate $pathFromRepoRoot from ${File(".").absolutePath}")
}
