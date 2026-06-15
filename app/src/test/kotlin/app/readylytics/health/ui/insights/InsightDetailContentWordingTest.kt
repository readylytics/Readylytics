package app.readylytics.health.ui.insights

import org.junit.Assert.assertTrue
import org.junit.Test

class InsightDetailContentWordingTest {
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
        val allText = InsightDetailResourceSpec.debugEnglishText().lowercase()

        forbidden.forEach { phrase ->
            assertTrue("Forbidden phrase present: $phrase", phrase !in allText)
        }
    }
}
