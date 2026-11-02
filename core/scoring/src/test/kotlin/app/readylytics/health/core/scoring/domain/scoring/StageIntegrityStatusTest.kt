package app.readylytics.health.core.scoring.domain.scoring

import org.junit.Test
import kotlin.test.assertEquals

class StageIntegrityStatusTest {

    private fun mockValidation(
        stagesValid: Boolean = true,
        stagesSuspicious: Boolean = false,
    ) = ScoringCalculator.NightValidationResult(
        rmssdValid = true,
        rhrValid = true,
        durationValid = true,
        stagesValid = stagesValid,
        stagesSuspicious = stagesSuspicious,
        hrCoverageValid = true,
    )

    @Test
    fun `returns NO_BREAKDOWN when duration is positive but deep and rem are both 0`() {
        val status = StageIntegrityEvaluator.evaluate(
            durationMinutes = 480,
            deepMinutes = 0,
            remMinutes = 0,
            validation = mockValidation(),
        )
        assertEquals(StageIntegrityStatus.NO_BREAKDOWN, status)
    }

    @Test
    fun `returns IMPLAUSIBLE_RATIO when validation indicates stages are invalid or suspicious`() {
        val invalidStatus = StageIntegrityEvaluator.evaluate(
            durationMinutes = 480,
            deepMinutes = 100,
            remMinutes = 100,
            validation = mockValidation(stagesValid = false),
        )
        assertEquals(StageIntegrityStatus.IMPLAUSIBLE_RATIO, invalidStatus)

        val suspiciousStatus = StageIntegrityEvaluator.evaluate(
            durationMinutes = 480,
            deepMinutes = 100,
            remMinutes = 100,
            validation = mockValidation(stagesSuspicious = true),
        )
        assertEquals(StageIntegrityStatus.IMPLAUSIBLE_RATIO, suspiciousStatus)
    }

    @Test
    fun `returns VALID when deep and rem are present and validation passes`() {
        val status = StageIntegrityEvaluator.evaluate(
            durationMinutes = 480,
            deepMinutes = 90,
            remMinutes = 90,
            validation = mockValidation(),
        )
        assertEquals(StageIntegrityStatus.VALID, status)
    }
}
