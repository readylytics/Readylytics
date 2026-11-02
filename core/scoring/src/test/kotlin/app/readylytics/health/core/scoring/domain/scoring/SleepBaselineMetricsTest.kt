package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.model.domain.model.SleepSession
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SleepBaselineMetricsTest {

    private fun mockValidation() = ScoringCalculator.NightValidationResult(
        rmssdValid = true,
        rhrValid = true,
        durationValid = true,
        stagesValid = true,
        stagesSuspicious = false,
        hrCoverageValid = true,
    )

    private fun mockSleepSession(
        durationMinutes: Int = 480,
        deepSleepMinutes: Int = 0,
        remSleepMinutes: Int = 0,
        lightSleepMinutes: Int = 0,
    ) = SleepSession(
        id = "session-1",
        startTime = 1000L,
        endTime = 5000L,
        durationMinutes = durationMinutes,
        efficiency = 90f,
        deepSleepMinutes = deepSleepMinutes,
        remSleepMinutes = remSleepMinutes,
        lightSleepMinutes = lightSleepMinutes,
        awakeMinutes = 0,
        sleepScore = null,
        startZoneOffsetSeconds = null,
        endZoneOffsetSeconds = null,
        deviceName = "Watch",
    )

    @Test
    fun `isStagesSuspicious returns true when deep and rem are 0 even if light is positive`() {
        // Models devices that report generic STAGE_TYPE_SLEEPING mapped to light
        val session = mockSleepSession(
            durationMinutes = 480,
            deepSleepMinutes = 0,
            remSleepMinutes = 0,
            lightSleepMinutes = 480,
        )

        val suspicious = isStagesSuspicious(session, mockValidation())

        assertTrue(suspicious, "Nights with no deep or rem sleep must trigger suspicious reweight")
    }

    @Test
    fun `isStagesSuspicious returns false for normal sleep sessions with deep and rem`() {
        val session = mockSleepSession(
            durationMinutes = 480,
            deepSleepMinutes = 90,
            remSleepMinutes = 90,
            lightSleepMinutes = 300,
        )

        val suspicious = isStagesSuspicious(session, mockValidation())

        assertFalse(suspicious)
    }
}
