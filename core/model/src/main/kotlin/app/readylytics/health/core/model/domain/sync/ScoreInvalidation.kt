package app.readylytics.health.core.model.domain.sync

import java.time.LocalDate

/**
 * R2-CACHE-001: bounds how far forward a rollup or retention deletion's date range must be
 * recomputed to cover every scoring lookback that could read the changed data.
 *
 * `daily_summaries` is derived from raw health data via walk-forward scoring lookbacks (acute/
 * chronic TRIMP, baselines, HRV sigma, circadian regularity, ...). When a tier rollup
 * (`DataRollupManager`, in the data layer) or a retention deletion (`RetentionCleanup`, in the
 * data layer) mutates raw data underneath an already-computed day, every later day whose lookback
 * window could have read that raw data is stale and must be recomputed. [affectedRange] widens the
 * touched date range forward by the longest such lookback so callers can enqueue a single bounded
 * recompute-only resync that covers every dependent day, capped at today (recomputing the future
 * is meaningless).
 */
object ScoreInvalidation {
    /**
     * Longest scoring lookback that reads historical data, in days — see
     * `ScoreInvalidationTest`'s depth-guard, which fails the build if a new lookback constant
     * exceeds this.
     */
    const val MAX_DEPENDENT_WINDOW_DAYS = 84L

    /** A closed date range `[start, endInclusive]`, plain data class per this codebase's
     * [app.readylytics.health.core.model.domain.util.RetentionBounds.HistoricalWindow] precedent
     * (no `LocalDate.rangeTo` operator extension exists here). */
    data class AffectedRange(val start: LocalDate, val endInclusive: LocalDate)

    /**
     * Widens [changed] forward by [MAX_DEPENDENT_WINDOW_DAYS] so every scoring lookback that could
     * have read the changed data is covered, capped so the result never extends past [today].
     */
    fun affectedRange(
        changed: AffectedRange,
        today: LocalDate,
    ): AffectedRange {
        val widenedEnd = changed.endInclusive.plusDays(MAX_DEPENDENT_WINDOW_DAYS)
        val end = if (widenedEnd.isAfter(today)) today else widenedEnd
        val boundedEnd = if (end.isBefore(changed.start)) changed.start else end
        return AffectedRange(changed.start, boundedEnd)
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
}
