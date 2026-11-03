package app.readylytics.health.core.scoring.domain.scoring

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StagesSuspiciousTest {
    private fun validation(
        stagesValid: Boolean = true,
        stagesSuspicious: Boolean = false,
    ) = ScoringCalculator.NightValidationResult(
        rmssdValid = true,
        rhrValid = true,
        durationValid = true,
        stagesValid = stagesValid,
        stagesSuspicious = stagesSuspicious,
    )

    @Test
    fun `validator stagesValid false flags night as suspicious even with stage breakdown present`() =
        assertTrue(isStagesSuspicious(validation(stagesValid = false)))

    @Test
    fun `validator stagesSuspicious true flags night as suspicious even with stage breakdown present`() =
        assertTrue(isStagesSuspicious(validation(stagesSuspicious = true)))

    @Test
    fun `valid stages with breakdown present is not suspicious`() =
        assertFalse(isStagesSuspicious(validation()))
}
