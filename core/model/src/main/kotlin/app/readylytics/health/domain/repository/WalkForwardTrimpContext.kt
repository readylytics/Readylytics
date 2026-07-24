package app.readylytics.health.domain.repository

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
 */
data class WalkForwardTrimpContext(
    val dailyTrimpByDate: TreeMap<LocalDate, Float>,
    val everydayTrimpByDate: TreeMap<LocalDate, Float>,
)
