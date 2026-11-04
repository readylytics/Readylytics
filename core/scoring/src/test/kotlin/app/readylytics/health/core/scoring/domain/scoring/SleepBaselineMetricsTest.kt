package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.model.domain.model.SleepSession
import app.readylytics.health.core.model.domain.scoring.ScoringConstants
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SleepBaselineMetricsTest {
    private val calculator = mockk<ScoringCalculator>(relaxed = true)

    private fun testSession(): SleepSession =
        SleepSession(
            id = "session-1",
            startTime = 1_000_000L,
            endTime = 1_000_000L + 480 * 60_000L,
            durationMinutes = 480,
            efficiency = 0.95f,
            deepSleepMinutes = 90,
            remSleepMinutes = 90,
            lightSleepMinutes = 300,
            awakeMinutes = 0,
        )

    private fun validNightResult(): ScoringCalculator.NightValidationResult =
        ScoringCalculator.NightValidationResult(
            rmssdValid = true,
            rhrValid = true,
            durationValid = true,
            stagesValid = true,
            stagesSuspicious = false,
        )

    @Test
    fun `computeBaselineMetrics coerces calculatedRhrSigma to RHR_MIN_SIGMA_BPM when sample variance is small`() {
        // RHR values with sample standard deviation ~ 0.5 bpm
        val lowVarianceRhr = listOf(60, 61, 60, 61, 60)
        val input =
            BaselineMetricsInput(
                frozenBaseline = false,
                frozenHrvSigma = null,
                sigmaHrvHistory = listOf(50f, 52f),
                sigmaPrior = 0.15f,
                sessionHrvSamples = listOf(50f),
                rhrValues = lowVarianceRhr,
                frozenRhrSigma = null,
                session = testSession(),
                validation = validNightResult(),
            )

        val result = computeBaselineMetrics(input, calculator)

        assertNotNull(result.calculatedRhrSigma)
        assertEquals(ScoringConstants.Restoration.RHR_MIN_SIGMA_BPM, result.calculatedRhrSigma!!, 0.001f)
        assertEquals(ScoringConstants.Restoration.RHR_MIN_SIGMA_BPM, result.effectiveRhrSigma!!, 0.001f)
    }
}
