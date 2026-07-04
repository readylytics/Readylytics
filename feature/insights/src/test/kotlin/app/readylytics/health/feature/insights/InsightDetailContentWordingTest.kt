package app.readylytics.health.feature.insights

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors each [InsightDetailResourceSpec]'s narrative copy in English so wording compliance can
 * be checked without an Android `Context` to resolve string resources. Keep in sync with the
 * actual `strings.xml`/`arrays.xml` content for each insight if that copy changes.
 */
class InsightDetailContentWordingTest {
    private val debugEnglishByInsight =
        listOf(
            "Blood Pressure Elevated During High Strain. One elevated reading does not diagnose hypertension.",
            "Late Bedtime May Have Affected Recovery. This does not prove your circadian rhythm shifted.",
            "High Strain and Short Sleep. This does not prove your recovery is impaired.",
            "HRV Below Baseline Multiple Nights. HRV below baseline does not identify the exact cause.",
            "Low HRV and Lower Oxygen Overnight. This does not diagnose sleep apnea or any medical condition.",
            "Late Heart Rate Nadir. A late heart rate nadir does not prove poor recovery.",
            "Delayed Recovery with Elevated Resting Heart Rate. " +
                "This does not diagnose illness or prove overtraining.",
            "Late Recovery and Short Sleep. This suggests less recovery opportunity.",
            "Training Load May Be Affecting Recovery. This does not indicate overtraining.",
            "Low RAS Despite Training Load. Low RAS does not mean your training was useless.",
            "Weekly RAS Below Target. A low RAS week is not automatically bad.",
            "HRV Data Missing. Readiness may be less personalized today.",
            "Sleep Stage Data Missing. Sleep score may be less complete.",
            "No Clear Recovery Rebound Yet. This does not mean the rest day failed.",
            "Recovery Rebound. This does not guarantee performance.",
            "Possible Illness Signal. This does not diagnose an infection.",
            "Low Daily Activity. A low step count is not automatically bad.",
            "Strong Recovery Signal. It does not mean you should automatically train harder than planned.",
            "Weight Change Under High Training Load. One-week change does not prove fat gain or fat loss.",
            "Training Load Carryover. A high TRIMP day does not automatically mean recovery is poor.",
        )

    @Test
    fun `detail strings avoid forbidden certainty wording`() {
        val forbidden =
            listOf(
                "proves",
                "diagnosed",
                "caused by",
                "you have sleep apnea",
                "you are sick",
                "you are overtrained",
            )
        val allText = debugEnglishByInsight.joinToString("\n").lowercase()

        forbidden.forEach { phrase ->
            assertTrue("Forbidden phrase present: $phrase", phrase !in allText)
        }
    }
}
