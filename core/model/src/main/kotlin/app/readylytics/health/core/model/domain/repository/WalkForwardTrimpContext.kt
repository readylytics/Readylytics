package app.readylytics.health.core.model.domain.repository

import java.time.LocalDate
import java.util.TreeMap

/**
 * PERF-002/WP-20: a single fetch of the workout-only and everyday-HR TRIMP series, held in memory
 * for the duration of one walk-forward (daily sync or resync recompute) and shared across every day
 * it recomputes, instead of each day independently re-querying its own 84-day lookback window.
 *
 * [TreeMap] specifically (not a plain [Map]) so per-day slicing via `subMap(from, true, to, true)`
 * reproduces the exact bounded-window `WHERE timestampMs >= :from AND timestampMs < :to` semantics
 * the per-day DB queries this replaces (`WorkoutDao.getTrimpPoints` / `DailySummaryDao
 * .getEverydayTrimpPoints`) used, in O(log n + k) instead of a full scan.
 *
 * [trackingStartDate] is the true first-data date (earliest workout or daily summary, outside the
 * 84-day window) for the minimum-history guard. It is fetched once, then lowered as each
 * walk-forward day commits (see `DailyTrimpComputer.publishTrimpToContext`), so a resync that
 * backfills before the pre-existing first row sees the same history a second run would read from
 * the DB. A context is always built from a completed lookup, so `null` unambiguously means
 * "no data exists yet" -- never "not fetched".
 */
data class WalkForwardTrimpContext(
    val dailyTrimpByDate: TreeMap<LocalDate, Float>,
    val everydayTrimpByDate: TreeMap<LocalDate, Float>,
    var trackingStartDate: LocalDate? = null,
) {
    /** Lowers [trackingStartDate] to [date] when [date] precedes it (or none is known yet). */
    fun registerTrackedDay(date: LocalDate) {
        val current = trackingStartDate
        if (current == null || date.isBefore(current)) trackingStartDate = date
    }
}
