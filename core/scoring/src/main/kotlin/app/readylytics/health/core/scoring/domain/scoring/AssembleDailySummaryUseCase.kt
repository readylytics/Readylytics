package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.scoring.domain.scoring.AssembleDailySummaryUseCase
import app.readylytics.health.core.scoring.domain.scoring.ResolveDailyBaselinesUseCase

import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.scoring.domain.scoring.components.Phase
import java.time.LocalDate
import javax.inject.Inject

class AssembleDailySummaryUseCase
    @Inject
    constructor() {
        fun assembleUncalibrated(
            baseSummary: DailySummary,
            hasSession: Boolean,
            avgSpo2: Float?,
            avgBodyTemp: Float?,
            calibHrvBaseline: Int?,
            rhrBaselineValue: Float,
            nocturnalHrv: Int? = null,
            restingHeartRate: Int? = null,
            sleepDurationMinutes: Int? = null,
            deepSleepPercent: Float? = null,
            remSleepPercent: Float? = null,
        ): DailySummary =
            baseSummary.copy(
                nocturnalHrv = if (hasSession) nocturnalHrv else baseSummary.nocturnalHrv,
                restingHeartRate = if (hasSession) restingHeartRate else baseSummary.restingHeartRate,
                sleepDurationMinutes = if (hasSession) sleepDurationMinutes else baseSummary.sleepDurationMinutes,
                deepSleepPercent = if (hasSession) deepSleepPercent else baseSummary.deepSleepPercent,
                remSleepPercent = if (hasSession) remSleepPercent else baseSummary.remSleepPercent,
                isCalibrating = true,
                avgSleepingSpo2 = avgSpo2,
                avgSleepingBodyTemp = avgBodyTemp,
                snapshotCalibrationPhase = Phase.CALIBRATION.name,
                hrvBaseline = calibHrvBaseline,
                rhrBpm = rhrBaselineValue,
            )

        fun assembleCalibrated(
            baseSummary: DailySummary,
            targetDate: LocalDate,
            computedHrvBaseline: Int?,
            finalBaselines: ResolveDailyBaselinesUseCase.FinalBaselines,
            avgSpo2: Float?,
            avgBodyTemp: Float?,
            resolvedHrMax: Float,
            scoringConfigRasScalingFactor: Float,
            prefs: UserPreferences,
        ): DailySummary =
            baseSummary.copy(
                hrvBaseline = computedHrvBaseline,
                rhrBpm = finalBaselines.rhrBpm,
                hrvMuMssd = finalBaselines.hrvMuMssd,
                hrvSigmaMssd = finalBaselines.hrvSigmaMssd,
                rhrSigma = finalBaselines.rhrSigma,
                baselineCalculatedAtDate = targetDate,
                avgSleepingSpo2 = avgSpo2,
                avgSleepingBodyTemp = avgBodyTemp,
                hrMax = baseSummary.hrMax ?: resolvedHrMax,
                rasScalingFactor = baseSummary.rasScalingFactor ?: scoringConfigRasScalingFactor,
                snapshotProfile = baseSummary.snapshotProfile ?: prefs.physiologyProfile.name,
                hrvSigmaPrior = baseSummary.hrvSigmaPrior ?: prefs.physiologyProfile.lnSigmaPrior,
                baselineObservationCount = baseSummary.baselineObservationCount,
            )
    }
