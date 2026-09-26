package app.readylytics.health.core.model.domain.sync

import java.time.LocalDate

/**
 * R2-CACHE-001 / WP-10 (CACHE-103): bounds how far forward a data change's date range must be
 * recomputed to cover every scoring lookback that could read the changed data.
 *
 * `daily_summaries` is derived from raw health data via walk-forward scoring lookbacks (acute/
 * chronic TRIMP, baselines, HRV sigma, circadian regularity, ...). When data mutates underneath
 * an already-computed day, every later day whose lookback window could have read that data is
 * stale and must be recomputed. [dependencyClosure] is the sole exported score dependency horizon,
 * widening the touched range through today (recomputing the future is meaningless) for all
 * non-recommendation reasons, and bounding recommendation examples to 30 days.
 */
object ScoreInvalidation {
    /**
     * Longest single scoring lookback that reads historical data, in days — see
     * `ScoreInvalidationTest`'s depth-guard, which fails the build if a new lookback constant
     * exceeds this. Used solely to assert no lookback grows past this, NOT as an invalidation
     * horizon.
     */
    const val MAX_DEPENDENT_WINDOW_DAYS = 84L

    /** A closed date range `[start, endInclusive]`, plain data class per this codebase's
     * [app.readylytics.health.core.model.domain.util.RetentionBounds.HistoricalWindow] precedent
     * (no `LocalDate.rangeTo` operator extension exists here). */
    data class AffectedRange(val start: LocalDate, val endInclusive: LocalDate)

    /**
     * WP-21 (CACHE-002): Reason for score invalidation.
     *
     * In Readylytics scoring, changes to baselines, workouts, vitals, or source records propagate
     * forward through subsequent days without a finite fatigue cutoff. All scoring reasons except
     * [RECOMMENDATION_EXAMPLES] invalidate the full retained suffix through today.
     * [RECOMMENDATION_EXAMPLES] is an additive recommendation-example eligibility lookback
     * bounded to 30 days.
     */
    enum class Reason {
        UNKNOWN,
        BASELINE,
        WORKOUT,
        LATEST_VALUE,
        SOURCE_DELETION,
        SOURCE_REPLACEMENT,
        RECOMMENDATION_EXAMPLES,
        HOT_TIER_ROLLUP,
        RETENTION_CLEANUP,
        RECORD_DELETION,
        INTERVAL_CORRECTION,
        RESTORE_REGENERATE,
        AUTHORITATIVE_SOURCE_REPLACEMENT,
    }

    /**
     * Converts a stored or persisted reason string to a [Reason], mapping unknown or legacy
     * values conservatively to [Reason.UNKNOWN] so they receive the full retained suffix
     * rather than an under-covering range.
     */
    fun reasonFromStored(value: String): Reason =
        when (value) {
            "BASELINE" -> Reason.BASELINE
            "WORKOUT" -> Reason.WORKOUT
            "LATEST_VALUE" -> Reason.LATEST_VALUE
            "SOURCE_DELETION" -> Reason.SOURCE_DELETION
            "SOURCE_REPLACEMENT" -> Reason.SOURCE_REPLACEMENT
            "RECOMMENDATION_EXAMPLES" -> Reason.RECOMMENDATION_EXAMPLES
            "HOT_TIER_ROLLUP" -> Reason.HOT_TIER_ROLLUP
            "RETENTION_CLEANUP" -> Reason.RETENTION_CLEANUP
            "RECORD_DELETION" -> Reason.RECORD_DELETION
            "INTERVAL_CORRECTION" -> Reason.INTERVAL_CORRECTION
            "RESTORE_REGENERATE" -> Reason.RESTORE_REGENERATE
            "AUTHORITATIVE_SOURCE_REPLACEMENT" -> Reason.AUTHORITATIVE_SOURCE_REPLACEMENT
            "UNKNOWN" -> Reason.UNKNOWN
            else -> Reason.UNKNOWN
        }

    /**
     * Bounds the conservative forward dependency closure for an affected data range [changed]
     * triggered by [reason], intersected with the retained scoring window `[retentionStart, today]`.
     *
     * In Readylytics, baseline changes affect subsequent workout TRIMP, which feeds subsequent
     * chronic training load and residual fatigue without a finite cutoff. Consequently,
     * all reasons except [Reason.RECOMMENDATION_EXAMPLES] invalidate the complete retained suffix
     * through [today]. [Reason.RECOMMENDATION_EXAMPLES] is bounded to a 30-day candidate selection
     * fan-out window `[changed.start, changed.endInclusive + 30]`, capped at [today].
     *
     * Returns null when the resulting intersection is empty (e.g. changes exclusively in the future
     * or recommendation fan-out predating retention).
     */
    fun dependencyClosure(
        changed: AffectedRange,
        reason: Reason,
        retentionStart: LocalDate,
        today: LocalDate,
    ): AffectedRange? {
        if (changed.start.isAfter(changed.endInclusive)) return null
        val start = maxOf(changed.start, retentionStart)
        val end =
            when (reason) {
                Reason.RECOMMENDATION_EXAMPLES ->
                    minOf(changed.endInclusive.plusDays(EXAMPLE_SELECTION_LOOKBACK_DAYS), today)
                else -> today
            }
        return if (start.isAfter(end)) null else AffectedRange(start, end)
    }

    /**
     * Merges multiple [AffectedRange] instances into their minimal bounding range, or returns null if
     * all inputs are null or empty.
     */
    fun merge(ranges: Iterable<AffectedRange?>): AffectedRange? {
        val nonNull = ranges.filterNotNull()
        if (nonNull.isEmpty()) return null
        val start = nonNull.minOf { it.start }
        val endInclusive = nonNull.maxOf { it.endInclusive }
        return AffectedRange(start, endInclusive)
    }

    /** Vararg overload of [merge]. */
    fun merge(vararg ranges: AffectedRange?): AffectedRange? = merge(ranges.asIterable())

    /**
     * Task 5: recommendation examples ([app.readylytics.health.core.scoring.domain.recommendation.SelectWorkoutRecommendationExamples]
     * via `WorkoutExampleLoader`/`MorningRecommendationAssembler`, `core:database`) draw candidate
     * workouts from a rolling `EXAMPLE_WINDOW_DAYS` (30-day) lookback ending at each day's wake
     * time. Correcting or deleting a workout can therefore change which examples are eligible for
     * every day up to [EXAMPLE_SELECTION_LOOKBACK_DAYS] after it -- a *distinct* dependency from
     * general scoring-formula lookbacks, modeled separately here so a future change to
     * [MAX_DEPENDENT_WINDOW_DAYS] can never silently under-cover it.
     */
    const val EXAMPLE_SELECTION_LOOKBACK_DAYS = 30L

    /**
     * Bounds the recompute range for a workout correction/deletion on [correctionDate] to the days
     * whose recommendation examples could have referenced it: `[correctionDate, correctionDate +
     * EXAMPLE_SELECTION_LOOKBACK_DAYS]`, intersected with `[retentionStart, today]` so the result
     * never reaches into the future (nothing to recompute there) or before what the app still
     * retains (nothing there to repair). Returns null when that intersection is empty (e.g. a
     * correction older than the retention window).
     *
     * Purely additive: this must never be used in place of a wider range already computed for
     * another reason (e.g. [dependencyClosure]) -- callers holding both should [merge] them, never pick
     * one over the other, so neither dependency's horizon is ever silently shrunk.
     */
    fun exampleFanOutRange(
        correctionDate: LocalDate,
        today: LocalDate,
        retentionStart: LocalDate,
    ): AffectedRange? {
        val naiveEnd = correctionDate.plusDays(EXAMPLE_SELECTION_LOOKBACK_DAYS)
        val start = maxOf(correctionDate, retentionStart)
        val end = minOf(naiveEnd, today)
        return if (end.isBefore(start)) null else AffectedRange(start, end)
    }
}
