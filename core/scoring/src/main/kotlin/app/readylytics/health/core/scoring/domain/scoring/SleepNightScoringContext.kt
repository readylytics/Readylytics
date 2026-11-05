package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepModifiers

/**
 * Stage outputs of `ComputeSleepMetricsUseCase.invoke` accumulated for one night, plus the pure
 * mappings into the downstream nocturnal-scorer / debug-log / summary-assembly inputs.
 * Structural carrier only: no scoring math lives here.
 */
internal data class SleepNightScoringContext(
    val request: SleepMetricsRequest,
    val scoringConfig: ScoringConfig,
    val frozenBaseline: Boolean,
    val baselineWindow: BaselineWindowResult,
    val yesterdaySummary: DailySummary?,
    val evidence: NightEvidence,
    val baselineMetrics: BaselineMetricsResult,
    val sleepModifiers: SleepModifiers,
    val calibrationState: CalibrationState,
) {
    val sigmaPrior: Float
        get() = request.prefs.physiologyProfile.lnSigmaPrior

    val liveBaselineRhr: Float?
        get() =
            if (frozenBaseline) {
                null
            } else {
                request.rhrBaselineValue.takeIf {
                    baselineWindow.rhrValues.isNotEmpty() || request.prefs.rhrBaselineOverride != null
                }
            }
}

internal fun SleepNightScoringContext.toNocturnalScoringInput(currentNocturnalRhr: Int): NocturnalScoringInput =
    NocturnalScoringInput(
        session = request.session,
        core = request.core,
        minHrTimestamp = evidence.minHrTimestamp,
        sessionHrvSamples = evidence.hrv.samples,
        currentHrvMean = evidence.hrv.mean,
        muHrvHistory = baselineWindow.muHrvHistory,
        effectiveSigmaHistory = baselineMetrics.effectiveSigmaHistory,
        sigmaPrior = sigmaPrior,
        frozenHrvMu = baselineWindow.frozenHrvMu,
        frozenHrvSigma = baselineWindow.frozenHrvSigma,
        currentNocturnalRhr = currentNocturnalRhr,
        rhrValues = baselineWindow.rhrValues,
        frozenRhr = baselineWindow.frozenRhr,
        effectiveRhrSigma = baselineMetrics.effectiveRhrSigma,
        baselineRhrValue = evidence.baselineRhrValue,
        liveBaselineRhr = liveBaselineRhr,
        prefs = request.prefs,
        scoringConfig = scoringConfig,
        stagesSuspicious = baselineMetrics.stagesSuspicious,
        sleepModifiers = sleepModifiers,
        frozenBaseline = frozenBaseline,
        isCalibrating = calibrationState.isCalibrating,
        yesterdaySummary = yesterdaySummary,
        loadScore = request.loadScore,
        loadScoreEverydayHr = request.loadScoreEverydayHr,
        summary = request.summary,
        hrvSigma = baselineMetrics.hrvSigma,
        architectureDurationMinutes = request.architectureDurationMinutes,
    )

internal fun SleepNightScoringContext.toDebugScoringSnapshot(
    nocturnalScoring: NocturnalScoringResult,
): DebugScoringSnapshot =
    DebugScoringSnapshot(
        targetDate = request.targetDate,
        dayMidnight = request.dayMidnight,
        dayEndMs = request.dayEndMs,
        frozenBaseline = frozenBaseline,
        isCalibrating = calibrationState.isCalibrating,
        hrvMuHistorySize = baselineWindow.muHrvHistory.size,
        rhrValuesSize = baselineWindow.rhrValues.size,
        sessionId = request.session.id,
        currentHrvMean = evidence.hrv.mean,
        currentNocturnalRhr = evidence.wakeHr.currentRestingHr,
        durationMinutes = request.session.durationMinutes,
        loadScore = request.loadScore,
        frozenHrvMu = baselineWindow.frozenHrvMu,
        frozenHrvSigma = baselineWindow.frozenHrvSigma,
        activeHrvMu = nocturnalScoring.readinessResult.diagnostics.rollingMu,
        activeHrvSigma = baselineMetrics.hrvSigma,
        frozenRhr = baselineWindow.frozenRhr,
        effectiveRhrSigma = baselineMetrics.effectiveRhrSigma,
        zLnHrv = nocturnalScoring.persistedZLnHrv,
        zRhr = nocturnalScoring.persistedZRhr,
        sRest = nocturnalScoring.sRest,
        sleepScore = nocturnalScoring.sleepScore,
        readinessScore = nocturnalScoring.readinessScore,
        recoveryFlags = nocturnalScoring.persistedFlags,
    )

internal fun SleepNightScoringContext.toSummaryAssemblyContext(
    nocturnalScoring: NocturnalScoringResult,
): SummaryAssemblyContext =
    SummaryAssemblyContext(
        summary = request.summary,
        session = request.session,
        sessionHrvSamples = evidence.hrv.samples,
        currentHrvMean = evidence.hrv.mean,
        currentRestingHr = evidence.wakeHr.currentRestingHr,
        restingHrRatio = evidence.wakeHr.restingHrRatio,
        restingHrBaseline = evidence.wakeHr.restingHrBaseline,
        liveBaselineRhr = liveBaselineRhr,
        frozenBaseline = frozenBaseline,
        muHrvHistory = baselineWindow.muHrvHistory,
        hrvSigma = baselineMetrics.hrvSigma,
        effectiveRhrSigma = baselineMetrics.effectiveRhrSigma,
        isCalibrating = calibrationState.isCalibrating,
        targetDate = request.targetDate,
        prefs = request.prefs,
        rasScalingFactor = scoringConfig.rasScalingFactor,
        validHistoricalSessionIds = baselineWindow.validHistoricalSessionIds,
        persistedZLnHrv = nocturnalScoring.persistedZLnHrv,
        persistedZRhr = nocturnalScoring.persistedZRhr,
        sessionPhase = calibrationState.phase?.name,
        observationCount = calibrationState.observationCount,
        readinessResult = nocturnalScoring.readinessResult,
        sRest = nocturnalScoring.sRest,
        sleepScore = nocturnalScoring.sleepScore,
        readinessScore = nocturnalScoring.readinessScore,
        readinessEverydayHr = nocturnalScoring.readinessEverydayHr,
    )
