package app.readylytics.health.docs

import app.readylytics.health.core.scoring.domain.util.HeartRateFormulas
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SCORE-104 / OD-3: every user-facing hrMax description states the truncation the code performs. */
class HrMaxDocumentationDriftTest {
    @Test
    fun `hrMax truncation is documented on every About surface`() {
        assertEquals(183, HeartRateFormulas.estimateMaxHr(35), "docs cite age 35 -> 183 bpm")
        val glossary =
            Regex("""<string\s+name="about_glossary_hrmax"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                .find(readRepoFile("feature/about/src/main/res/values/strings.xml"))
                ?.groupValues
                ?.get(1) ?: error("Missing about_glossary_hrmax resource")

        for ((surface, text) in listOf(
            "ABOUT.md" to readRepoFile("ABOUT.md"),
            "docs/about.md" to readRepoFile("docs/about.md"),
            "about_glossary_hrmax" to glossary,
        )) {
            for (phrase in listOf("208 − 0.7 × age", "rounded down", "183 bpm")) {
                assertTrue(text.contains(phrase), "$surface must contain '$phrase'")
            }
        }
    }

    private fun readRepoFile(pathFromRepoRoot: String): String =
        listOf(File(pathFromRepoRoot), File("../$pathFromRepoRoot"), File("../../$pathFromRepoRoot"))
            .firstOrNull { it.exists() }
            ?.readText()
            ?: error("could not locate $pathFromRepoRoot from ${File(".").absolutePath}")
}
