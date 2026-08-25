package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.SleepSession
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.scoring.domain.util.stdev
import kotlin.math.exp
import kotlin.math.ln

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
    baseline > 0 && current.toFloat() / baseline.toFloat() <= threshold

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

internal data class HrvZScoreContext(
    val sessionHrvSamples: List<Float>,
    val currentHrvMean: Float,
    val muHrvHistory: List<Float>,
    val effectiveSigmaHistory: List<Float>,
    val sigmaPrior: Float,
    val frozenHrvMu: Float?,
    val frozenHrvSigma: Float?,
    val prefs: UserPreferences,
)

internal fun computeHrvZScore(
    ctx: HrvZScoreContext,
    scoringCalculator: ScoringCalculator,
): Float? {
    if (ctx.sessionHrvSamples.isNotEmpty()) {
        return scoringCalculator.computeHrvZScore(
            ctx.currentHrvMean,
            ctx.muHrvHistory,
            ctx.effectiveSigmaHistory,
            ctx.sigmaPrior,
            baselineOverride = ctx.prefs.hrvBaselineOverride,
            frozenLnMu = ctx.frozenHrvMu,
            frozenLnSigma = ctx.frozenHrvSigma,
        )
    }
    return null
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
