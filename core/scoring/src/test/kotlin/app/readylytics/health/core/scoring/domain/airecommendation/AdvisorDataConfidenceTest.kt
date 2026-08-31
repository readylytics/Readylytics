package app.readylytics.health.core.scoring.domain.airecommendation

import app.readylytics.health.core.model.domain.scoring.LoadCoverageConfidence
import app.readylytics.health.core.scoring.domain.scoring.components.Phase
import org.junit.Assert.assertEquals
import org.junit.Test

class AdvisorDataConfidenceTest {
    @Test
    fun `resolve uses calibration and recovery signal base confidence`() {
        assertEquals(AdvisorDataConfidence.LOW, resolveAdvisorConfidence(Phase.CALIBRATION, false, null))
        assertEquals(AdvisorDataConfidence.LOW, resolveAdvisorConfidence(Phase.EARLY_BASELINE, false, null))
        assertEquals(AdvisorDataConfidence.MEDIUM, resolveAdvisorConfidence(Phase.MATURING, false, null))
        assertEquals(AdvisorDataConfidence.LOW, resolveAdvisorConfidence(Phase.MATURING, true, null))
        assertEquals(AdvisorDataConfidence.HIGH, resolveAdvisorConfidence(Phase.MATURE, false, null))
        assertEquals(AdvisorDataConfidence.MEDIUM, resolveAdvisorConfidence(Phase.MATURE, true, null))
    }

    @Test
    fun `resolve keeps confidence unchanged for absent medium and high everyday coverage`() {
        listOf(null, LoadCoverageConfidence.MEDIUM, LoadCoverageConfidence.HIGH).forEach { coverage ->
            assertEquals(
                AdvisorDataConfidence.HIGH,
                resolveAdvisorConfidence(Phase.MATURE, false, coverage),
            )
        }
    }

    @Test
    fun `resolve low everyday coverage caps high confidence at medium`() {
        assertEquals(
            AdvisorDataConfidence.MEDIUM,
            resolveAdvisorConfidence(Phase.MATURE, false, LoadCoverageConfidence.LOW),
        )
    }

    @Test
    fun `resolve none everyday coverage downgrades one level with a low floor`() {
        assertEquals(
            AdvisorDataConfidence.MEDIUM,
            resolveAdvisorConfidence(Phase.MATURE, false, LoadCoverageConfidence.NONE),
        )
        assertEquals(
            AdvisorDataConfidence.LOW,
            resolveAdvisorConfidence(Phase.MATURING, false, LoadCoverageConfidence.NONE),
        )
        assertEquals(
            AdvisorDataConfidence.LOW,
            resolveAdvisorConfidence(Phase.CALIBRATION, false, LoadCoverageConfidence.NONE),
        )
    }

    @Test
    fun `resolve coverage tiers preserve recovery signal downgrades`() {
        listOf(
            null to AdvisorDataConfidence.MEDIUM,
            LoadCoverageConfidence.LOW to AdvisorDataConfidence.MEDIUM,
            LoadCoverageConfidence.MEDIUM to AdvisorDataConfidence.MEDIUM,
            LoadCoverageConfidence.HIGH to AdvisorDataConfidence.MEDIUM,
            LoadCoverageConfidence.NONE to AdvisorDataConfidence.LOW,
        ).forEach { (coverage, expectedConfidence) ->
            assertEquals(
                expectedConfidence,
                resolveAdvisorConfidence(Phase.MATURE, true, coverage),
            )
        }
        assertEquals(
            AdvisorDataConfidence.LOW,
            resolveAdvisorConfidence(Phase.MATURING, true, LoadCoverageConfidence.NONE),
        )
    }
}
