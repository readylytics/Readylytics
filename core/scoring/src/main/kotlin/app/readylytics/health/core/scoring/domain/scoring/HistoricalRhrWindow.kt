package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.model.domain.scoring.ScoringConstants
import java.time.LocalDate

/**
 * The RHR baseline membership window for [scoreDay]: the last [ScoringConstants.BASELINE_DAYS]
 * days strictly prior to [scoreDay] ([scoreDay - BASELINE_DAYS, scoreDay)).
 *
 * Aligned with HRV's `priorSleepDays` population (which strictly excludes the day being scored),
 * the RHR baseline excludes the current night's nadir/percentile. This eliminates self-inclusion
 * attenuation of RHR Z-scores and ensures overnight metrics are measured against prior history.
 *
 * Pure, no I/O -- shared by the historical backfill batch path
 * ([BaselineComputer.computeDayBackfillBaseline]) and the live per-day paths
 * ([BaselineComputer.computeAdaptiveBaselineRhrBpmBetween], [BaselineComputer.rhrHistoryBetween])
 * so every RHR-window-consuming code path applies the exact same membership rule regardless
 * of how it assembled its [HistoricalSleepDay] list.
 */
internal fun historicalRhrWindow(
    days: List<HistoricalSleepDay>,
    scoreDay: LocalDate,
): List<HistoricalSleepDay> {
    val first = scoreDay.minusDays(ScoringConstants.BASELINE_DAYS)
    return days.filter { it.scoreDay >= first && it.scoreDay < scoreDay }
}
