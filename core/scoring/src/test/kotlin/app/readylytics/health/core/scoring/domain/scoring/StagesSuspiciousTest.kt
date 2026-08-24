package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.model.domain.model.SleepSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StagesSuspiciousTest {
    private fun session(
        durationMinutes: Int = 480,
        deepSleepMinutes: Int = 80,
        remSleepMinutes: Int = 96,
        lightSleepMinutes: Int = 304,
    ) = SleepSession(
        id = "s1",
        startTime = 0L,
        endTime = durationMinutes * 60_000L,
        durationMinutes = durationMinutes,
        efficiency = 95f,
        deepSleepMinutes = deepSleepMinutes,
        remSleepMinutes = remSleepMinutes,
        lightSleepMinutes = lightSleepMinutes,
        awakeMinutes = 0,
    )

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
        assertTrue(isStagesSuspicious(session(), validation(stagesValid = false)))

    @Test
    fun `validator stagesSuspicious true flags night as suspicious even with stage breakdown present`() =
        assertTrue(isStagesSuspicious(session(), validation(stagesSuspicious = true)))

    @Test
    fun `valid stages with breakdown present is not suspicious`() =
        assertFalse(isStagesSuspicious(session(), validation()))

    @Test
    fun `zero stage breakdown with positive duration is suspicious regardless of validator`() =
        assertTrue(
            isStagesSuspicious(
                session(deepSleepMinutes = 0, remSleepMinutes = 0, lightSleepMinutes = 0),
                validation(),
            ),
        )
}
