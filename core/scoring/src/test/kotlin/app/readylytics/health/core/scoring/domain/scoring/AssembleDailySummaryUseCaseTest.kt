package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.scoring.domain.scoring.AssembleDailySummaryUseCase
import app.readylytics.health.core.scoring.domain.scoring.ResolveDailyBaselinesUseCase

import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.preferences.PhysiologyProfile
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class AssembleDailySummaryUseCaseTest {
    private lateinit var useCase: AssembleDailySummaryUseCase

    @Before
    fun setup() {
        useCase = AssembleDailySummaryUseCase()
    }

    @Test
    fun `assembleUncalibrated creates calibrating summary without session`() {
        val date = LocalDate.of(2026, 6, 15)
        val result =
            useCase.assembleUncalibrated(
                baseSummary = DailySummary(date = date),
                hasSession = false,
                avgSpo2 = 97f,
                avgBodyTemp = 36.5f,
                calibHrvBaseline = 50,
                rhrBaselineValue = 58f,
            )

        assertTrue(result.isCalibrating)
        assertEquals("CALIBRATION", result.snapshotCalibrationPhase)
        assertEquals(50, result.hrvBaseline)
        assertEquals(58f, result.rhrBpm)
        assertEquals(97f, result.avgSleepingSpo2)
        assertEquals(36.5f, result.avgSleepingBodyTemp)
    }

    @Test
    fun `assembleUncalibrated creates calibrating summary with session`() {
        val date = LocalDate.of(2026, 6, 15)
        val result =
            useCase.assembleUncalibrated(
                baseSummary = DailySummary(date = date),
                hasSession = true,
                avgSpo2 = 97f,
                avgBodyTemp = 36.5f,
                calibHrvBaseline = 50,
                rhrBaselineValue = 58f,
                nocturnalHrv = 45,
                restingHeartRate = 55,
                sleepDurationMinutes = 480,
                deepSleepPercent = 20f,
                remSleepPercent = 25f,
            )

        assertTrue(result.isCalibrating)
        assertEquals("CALIBRATION", result.snapshotCalibrationPhase)
        assertEquals(50, result.hrvBaseline)
        assertEquals(58f, result.rhrBpm)
        assertEquals(97f, result.avgSleepingSpo2)
        assertEquals(36.5f, result.avgSleepingBodyTemp)
        assertEquals(45, result.nocturnalHrv)
        assertEquals(55, result.restingHeartRate)
        assertEquals(480, result.sleepDurationMinutes)
        assertEquals(20f, result.deepSleepPercent)
        assertEquals(25f, result.remSleepPercent)
    }

    @Test
    fun `assembleCalibrated sets all load sleep and baseline metrics`() {
        val date = LocalDate.of(2026, 6, 15)
        val prefs = UserPreferences(physiologyProfile = PhysiologyProfile.ATHLETE)
        val finalBaselines =
            ResolveDailyBaselinesUseCase.FinalBaselines(
                hrvMuMssd = 4.0f,
                hrvSigmaMssd = 0.3f,
                rhrBpm = 50f,
                rhrSigma = 1.2f,
            )

        val result =
            useCase.assembleCalibrated(
                baseSummary = DailySummary(date = date),
                targetDate = date,
                computedHrvBaseline = 65,
                finalBaselines = finalBaselines,
                avgSpo2 = 98f,
                avgBodyTemp = 36.6f,
                resolvedHrMax = 192f,
                scoringConfigRasScalingFactor = 0.25f,
                prefs = prefs,
            )

        assertEquals(date, result.baselineCalculatedAtDate)
        assertEquals(65, result.hrvBaseline)
        assertEquals(4.0f, result.hrvMuMssd)
        assertEquals(0.3f, result.hrvSigmaMssd)
        assertEquals(50f, result.rhrBpm)
        assertEquals(1.2f, result.rhrSigma)
        assertEquals(192f, result.hrMax)
        assertEquals(0.25f, result.rasScalingFactor)
        assertEquals(PhysiologyProfile.ATHLETE.name, result.snapshotProfile)
    }

    @Test
    fun `assembleCalibrated preserves existing frozen hrMax rasScalingFactor and profile`() {
        val date = LocalDate.of(2026, 6, 15)
        val prefs = UserPreferences(physiologyProfile = PhysiologyProfile.ATHLETE)
        val finalBaselines =
            ResolveDailyBaselinesUseCase.FinalBaselines(
                hrvMuMssd = 4.0f,
                hrvSigmaMssd = 0.3f,
                rhrBpm = 50f,
                rhrSigma = 1.2f,
            )

        val existingSummary =
            DailySummary(
                date = date,
                hrMax = 200f,
                rasScalingFactor = 0.30f,
                snapshotProfile = "CUSTOM",
                hrvSigmaPrior = 0.5f,
                baselineObservationCount = 14,
            )

        val result =
            useCase.assembleCalibrated(
                baseSummary = existingSummary,
                targetDate = date,
                computedHrvBaseline = 65,
                finalBaselines = finalBaselines,
                avgSpo2 = 98f,
                avgBodyTemp = 36.6f,
                resolvedHrMax = 192f,
                scoringConfigRasScalingFactor = 0.25f,
                prefs = prefs,
            )

        assertEquals(200f, result.hrMax)
        assertEquals(0.30f, result.rasScalingFactor)
        assertEquals("CUSTOM", result.snapshotProfile)
        assertEquals(0.5f, result.hrvSigmaPrior)
        assertEquals(14, result.baselineObservationCount)
    }
}
