package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.ReadinessResult
import app.readylytics.health.core.model.domain.model.SleepSession
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.scoring.domain.util.HeartRateFormulas
import app.readylytics.health.core.scoring.domain.util.stdev
import java.time.LocalDate
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

internal data class BaselineMetricsInput(
    val frozenBaseline: Boolean,
    val frozenHrvSigma: Float?,
    val sigmaHrvHistory: List<Float>,
    val sigmaPrior: Float,
    val sessionHrvSamples: List<Float>,
    val rhrValues: List<Int>,
    val frozenRhrSigma: Float?,
    val session: SleepSession,
    val validation: ScoringCalculator.NightValidationResult,
)

internal data class BaselineMetricsResult(
    val effectiveSigmaHistory: List<Float>,
    val calculatedRhrSigma: Float?,
    val effectiveRhrSigma: Float?,
    val hrvSigma: Float?,
    val stagesSuspicious: Boolean,
)

internal fun resolveCurrentHrvBaseline(
    frozenBaseline: Boolean,
    frozenHrvMu: Float?,
    prefs: UserPreferences,
    muHrvHistory: List<Float>,
): Float? =
    when {
        prefs.hrvBaselineOverride != null -> prefs.hrvBaselineOverride
        frozenBaseline && frozenHrvMu != null -> exp(frozenHrvMu)
        muHrvHistory.isNotEmpty() ->
            exp(
                muHrvHistory
                    .map { ln(it.coerceAtLeast(0.001f)) }
                    .average()
                    .toFloat(),
            )
        else -> null
    }

internal fun isHrvOptimal(baseline: Float?, current: Float, threshold: Float): Boolean =
    baseline != null && baseline > 0f && current / baseline >= threshold

internal fun isRhrOptimal(baseline: Int, current: Int, threshold: Float): Boolean =
    baseline > 0 && threshold > 0f && current.toFloat() / baseline.toFloat() <= 1f / threshold

internal fun isPreviousHrvOptimal(
    yesterdaySummary: DailySummary?,
    yesterdayHrvBaseline: Float?,
    threshold: Float,
): Boolean {
    val prevHrv = yesterdaySummary?.nocturnalHrv ?: return false
    return yesterdayHrvBaseline != null &&
        yesterdayHrvBaseline > 0f &&
        prevHrv.toFloat() / yesterdayHrvBaseline >= threshold
}

internal fun isStagesSuspicious(
    session: SleepSession,
    validation: ScoringCalculator.NightValidationResult,
): Boolean {
    val hasNoStageBreakdown =
        session.durationMinutes > 0 &&
            session.deepSleepMinutes == 0 &&
            session.remSleepMinutes == 0 &&
            session.lightSleepMinutes == 0
    return hasNoStageBreakdown || !validation.stagesValid || validation.stagesSuspicious
}

internal fun computeBaselineMetrics(
    input: BaselineMetricsInput,
    scoringCalculator: ScoringCalculator,
): BaselineMetricsResult {
    val effectiveSigmaHistory =
        if (input.frozenBaseline && input.frozenHrvSigma != null) {
            listOf(input.frozenHrvSigma)
        } else {
            input.sigmaHrvHistory
        }

    val calculatedRhrSigma =
        if (!input.frozenBaseline && input.rhrValues.size > 1) {
            input.rhrValues.stdev().takeIf { it > 0f }
        } else {
            null
        }
    val effectiveRhrSigma = input.frozenRhrSigma ?: calculatedRhrSigma

    val hrvSigma =
        if (input.sessionHrvSamples.isNotEmpty()) {
            if (input.frozenBaseline && input.frozenHrvSigma != null) {
                input.frozenHrvSigma
            } else {
                val lnSigmaHistory = effectiveSigmaHistory.map { ln(it.coerceAtLeast(0.001f)) }
                scoringCalculator.hrvSigma(lnSigmaHistory, input.sigmaPrior)
            }
        } else {
            null
        }

    val stagesSuspicious = isStagesSuspicious(input.session, input.validation)

    return BaselineMetricsResult(
        effectiveSigmaHistory = effectiveSigmaHistory,
        calculatedRhrSigma = calculatedRhrSigma,
        effectiveRhrSigma = effectiveRhrSigma,
        hrvSigma = hrvSigma,
        stagesSuspicious = stagesSuspicious,
    )
}

internal data class SummaryAssemblyContext(
    val summary: DailySummary,
    val session: SleepSession,
    val sessionHrvSamples: List<Float>,
    val currentHrvMean: Float,
    val currentRestingHr: Int?,
    val restingHrRatio: Float?,
    val restingHrBaseline: Int?,
    val frozenBaseline: Boolean,
    val muHrvHistory: List<Float>,
    val hrvSigma: Float?,
    val effectiveRhrSigma: Float?,
    val isCalibrating: Boolean,
    val targetDate: LocalDate,
    val prefs: UserPreferences,
    val rasScalingFactor: Float,
    val validHistoricalSessionIds: List<String>,
    val persistedZLnHrv: Float?,
    val persistedZRhr: Float?,
    val sessionPhase: String,
    val readinessResult: ReadinessResult,
    val sRest: Float?,
    val sleepScore: Float?,
    val readinessScore: Float?,
    val readinessEverydayHr: Float?,
)

internal data class BaselineCalibrationSnapshots(
    val calculatedAtDate: LocalDate?,
    val hrMax: Float?,
    val rasScalingFactor: Float?,
    val snapshotProfile: String?,
    val hrvSigmaPrior: Float?,
    val observationCount: Int?,
)

internal fun resolveCalibrationSnapshots(ctx: SummaryAssemblyContext): BaselineCalibrationSnapshots =
    if (ctx.frozenBaseline) {
        BaselineCalibrationSnapshots(
            calculatedAtDate = ctx.summary.baselineCalculatedAtDate,
            hrMax = ctx.summary.hrMax,
            rasScalingFactor = ctx.summary.rasScalingFactor,
            snapshotProfile = ctx.summary.snapshotProfile,
            hrvSigmaPrior = ctx.summary.hrvSigmaPrior,
            observationCount = ctx.summary.baselineObservationCount,
        )
    } else if (!ctx.isCalibrating) {
        BaselineCalibrationSnapshots(
            calculatedAtDate = ctx.targetDate,
            hrMax = HeartRateFormulas.resolveMaxHeartRate(ctx.prefs),
            rasScalingFactor = ctx.rasScalingFactor,
            snapshotProfile = ctx.prefs.physiologyProfile.name,
            hrvSigmaPrior = ctx.prefs.physiologyProfile.lnSigmaPrior,
            observationCount = ctx.validHistoricalSessionIds.size,
        )
    } else {
        BaselineCalibrationSnapshots(
            calculatedAtDate = null,
            hrMax = null,
            rasScalingFactor = null,
            snapshotProfile = null,
            hrvSigmaPrior = null,
            observationCount = null,
        )
    }

internal fun resolveRollingHrvMu(
    frozenBaseline: Boolean,
    frozenMu: Float?,
    muHrvHistory: List<Float>,
): Float? =
    if (frozenBaseline) {
        frozenMu
    } else if (muHrvHistory.isNotEmpty()) {
        muHrvHistory.map { ln(it.coerceAtLeast(0.001f)) }.average().toFloat()
    } else {
        null
    }

internal fun computeSleepStagePercent(durationMinutes: Int, stageMinutes: Int): Float? =
    if (durationMinutes > 0) {
        stageMinutes / durationMinutes.toFloat() * 100f
    } else {
        null
    }

internal fun assembleDailySummary(ctx: SummaryAssemblyContext): DailySummary {
    val durationMinutes = ctx.session.durationMinutes
    val deepPercent = computeSleepStagePercent(durationMinutes, ctx.session.deepSleepMinutes)
    val remPercent = computeSleepStagePercent(durationMinutes, ctx.session.remSleepMinutes)
    val hrvMu = resolveRollingHrvMu(ctx.frozenBaseline, ctx.summary.hrvMuMssd, ctx.muHrvHistory)
    val rhrBpm = if (ctx.frozenBaseline) ctx.summary.rhrBpm else ctx.restingHrBaseline?.toFloat()
    val rhrSigma = if (ctx.frozenBaseline) ctx.summary.rhrSigma else ctx.effectiveRhrSigma
    val hrvSigmaMssd = if (ctx.frozenBaseline) ctx.summary.hrvSigmaMssd else ctx.hrvSigma
    val snapshots = resolveCalibrationSnapshots(ctx)

    return ctx.summary.copy(
        sleepScore = ctx.sleepScore,
        readinessWorkoutOnly = ctx.readinessScore,
        readinessEverydayHr = ctx.readinessEverydayHr,
        nocturnalHrv = if (ctx.sessionHrvSamples.isNotEmpty()) ctx.currentHrvMean.roundToInt() else null,
        sleepDurationMinutes = durationMinutes,
        deepSleepPercent = deepPercent,
        remSleepPercent = remPercent,
        restingHeartRate = ctx.currentRestingHr,
        restingHrRatio = ctx.restingHrRatio,
        hrvMuMssd = hrvMu,
        hrvSigmaMssd = hrvSigmaMssd,
        rhrBpm = rhrBpm,
        rhrSigma = rhrSigma,
        baselineCalculatedAtDate = snapshots.calculatedAtDate,
        hrMax = snapshots.hrMax,
        rasScalingFactor = snapshots.rasScalingFactor,
        snapshotProfile = snapshots.snapshotProfile,
        hrvSigmaPrior = snapshots.hrvSigmaPrior,
        baselineObservationCount = snapshots.observationCount,
        zLnHrv = ctx.persistedZLnHrv,
        zRhr = ctx.persistedZRhr,
        recoveryFlags = ctx.readinessResult.recoveryFlags,
        hrvSigma = ctx.hrvSigma,
        snapshotCalibrationPhase = ctx.sessionPhase,
        readinessResult = ctx.readinessResult,
        sRest = ctx.sRest,
    )
}
