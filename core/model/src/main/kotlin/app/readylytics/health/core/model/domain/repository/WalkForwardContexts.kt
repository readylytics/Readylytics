package app.readylytics.health.core.model.domain.repository

/**
 * The three prefetched-once contexts a multi-day walk-forward shares across every day it scores.
 *
 * Passing them as one holder rather than as separate overloads removes a latent performance trap:
 * a partially populated call used to silently select the context-less path, which re-queries every
 * lookback window per day and, for [fatigue], re-sums the full retained history per day — turning
 * the documented O(W + D) reconstruction into O(D × W). A null field here means "not available for
 * this run"; each computer already handles that individually.
 *
 * The default (all null) is the single-day, no-walk-forward case.
 */
data class WalkForwardContexts(
    val trimp: WalkForwardTrimpContext? = null,
    val baseline: WalkForwardBaselineContext? = null,
    val fatigue: WalkForwardFatigueContext? = null,
    val vo2Max: WalkForwardVo2MaxContext? = null,
)
