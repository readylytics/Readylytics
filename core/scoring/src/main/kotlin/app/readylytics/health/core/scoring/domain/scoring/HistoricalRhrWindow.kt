package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.model.domain.scoring.ScoringConstants
import java.time.LocalDate

/**
 * The RHR baseline membership window for [scoreDay]: the last [ScoringConstants.BASELINE_DAYS]
 * days up to and including [scoreDay] itself.
 *
 * Unlike the HRV `priorSleepDays` population (which strictly excludes the day being scored --
 * see the HRV mu/sigma windows in [BaselineComputer.computeHrvWindowsBetween]), the RHR baseline
 * includes the current night's nadir/percentile when it is otherwise eligible. OD-5 keeps these
 * two populations deliberately distinct; this function must never be reused to unify them.
 *
 * Pure, no I/O -- shared by the historical backfill batch path
 * ([BaselineComputer.computeDayBackfillBaseline]) and the live per-day paths
 * ([BaselineComputer.computeAdaptiveBaselineRhrBpm], [BaselineComputer.computeAdaptiveBaselineRhrBpmBetween],
 * [BaselineComputer.rhrHistoryBetween]) so every RHR-window-consuming code path applies the exact
 * same membership rule regardless of how it assembled its [HistoricalSleepDay] list.
 */
internal fun historicalRhrWindow(
    days: List<HistoricalSleepDay>,
    scoreDay: LocalDate,
): List<HistoricalSleepDay> {
    val first = scoreDay.minusDays(ScoringConstants.BASELINE_DAYS)
    return days.filter { it.scoreDay >= first && it.scoreDay <= scoreDay }
}
