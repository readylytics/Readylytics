package app.readylytics.health.domain.repository

import app.readylytics.health.domain.model.SleepSession

/**
 * PERF-002/WP-22: sleep sessions covering the widest RHR/HRV baseline lookback (56 days), fetched
 * once for the duration of one walk-forward (daily sync or resync recompute) and shared across
 * every day it recomputes, instead of each day independently re-querying its own 30- or 56-day
 * lookback window (`BaselineComputer.computeAdaptiveBaselineRhrBpmBetween`/
 * `computeHrvWindowsBetween`/`computeHrvBaselineBetween`).
 *
 * [sessions] is sorted ascending by `startTime` (matching `SleepSessionDao.getBetween`'s order) so
 * per-day slicing reproduces the exact `startTime >= from AND endTime <= to` bounded-window
 * semantics those per-day queries used.
 */
data class WalkForwardBaselineContext(
    val sessions: List<SleepSession>,
)
