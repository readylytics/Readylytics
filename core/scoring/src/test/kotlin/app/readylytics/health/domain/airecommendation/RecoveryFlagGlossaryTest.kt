package app.readylytics.health.domain.airecommendation

import app.readylytics.health.domain.model.RecoveryFlag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryFlagGlossaryTest {
    @Test
    fun `every recovery flag has a nonblank glossary entry`() {
        assertEquals(RecoveryFlag.entries.toSet(), RecoveryFlagGlossary.entries.keys)
        RecoveryFlag.entries.forEach { flag ->
            assertTrue("Glossary blank for $flag", RecoveryFlagGlossary.explain(flag).isNotBlank())
        }
    }

    @Test
    fun `glossary matches the template's drafted plain-English meanings`() {
        assertEquals(
            "training load has risen faster than your fitness can currently absorb",
            RecoveryFlagGlossary.explain(RecoveryFlag.OVERREACHING),
        )
        assertEquals(
            "your recovery markers are notably above your personal norm today",
            RecoveryFlagGlossary.explain(RecoveryFlag.STRONG_RECOVERY_SIGNAL),
        )
        assertEquals(
            "your overnight recovery signals are unusually different from your normal baseline",
            RecoveryFlagGlossary.explain(RecoveryFlag.ILLNESS_ONSET),
        )
        assertEquals(
            "yesterday's rest day is showing up as a recovery benefit today",
            RecoveryFlagGlossary.explain(RecoveryFlag.REST_DAY_SUCCESS),
        )
    }

    @Test
    fun `every entry is plain English without placeholders or code tokens`() {
        RecoveryFlag.entries.forEach { flag ->
            val gloss = RecoveryFlagGlossary.explain(flag)
            assertTrue(gloss.none { it in "{{}}#" })
        }
    }
}
