package app.readylytics.health.feature.dashboard.usecase

/**
 * Outcome of the dashboard's live residual-fatigue lookup.
 *
 * A plain `Float?` cannot express this: "no live value" means two different things with two
 * different fallbacks. For a finished day the persisted end-of-day snapshot is the correct final
 * answer; for the current day a missing live value means the metric is genuinely *unknown*, and
 * substituting the snapshot would silently understate fatigue (the snapshot's never-backfilled gate
 * only looks at workouts starting before today, so a workout logged today whose TRIMP was never
 * backfilled contributes zero to it instead of blocking it). Modelling the two cases separately
 * makes that conflation unrepresentable.
 */
sealed interface LiveResidualFatigue {
    /** Selected day already ended — use the persisted `DailySummary.residualFatigue` snapshot. */
    data object NotApplicable : LiveResidualFatigue

    /**
     * Current day, but no live value could be produced: the metric is disabled, a retained workout
     * was never backfilled (unknown, not low — HIGH-2), or the lookup itself failed. Renders
     * NO_DATA; must never fall through to the persisted snapshot.
     */
    data object Unavailable : LiveResidualFatigue

    /** Current day, fatigue decayed through the present moment. */
    data class Value(
        val fatigue: Float,
    ) : LiveResidualFatigue
}
