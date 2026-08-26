package app.readylytics.health.feature.workouts

import app.readylytics.health.core.scoring.domain.workouts.weekly.DailyTrainingVolume
import app.readylytics.health.core.ui.common.DailyDataPoint

/** Result of comparing one day's current-week cumulative minutes against the previous week's. */
internal data class DailyDeltaResult(
    val deltaMinutes: Int,
    val percentChange: Float?,
)

/** Pure mapping from [DailyTrainingVolume] (the shared weekly-aggregation model) to the
 *  cumulative-volume chart's Vico series and per-day tooltip values. Zero Android dependencies. */
internal object WeeklyVolumeTrendMapper {
    /** Current-week and previous-week cumulative series, in that order. Current-week points for
     *  days strictly after today are omitted entirely (not fed to Vico as nulls) so the line
     *  stops exactly at today; previous-week always has all 7 points. */
    fun toSeries(daily: List<DailyTrainingVolume>): Pair<List<DailyDataPoint>, List<DailyDataPoint>> {
        val current =
            daily.mapNotNull { day ->
                day.currentWeekCumulativeMinutes?.let { DailyDataPoint(day.dayOffset, it.toFloat()) }
            }
        val previous = daily.map { day -> DailyDataPoint(day.dayOffset, day.previousWeekCumulativeMinutes.toFloat()) }
        return current to previous
    }

    /** The day offset of the last entry with actual current-week data, i.e. "today" relative to
     *  the anchor date the stats were computed for. Null when the current week has no data yet. */
    fun todayOffset(daily: List<DailyTrainingVolume>): Int? =
        daily.lastOrNull { it.currentWeekCumulativeMinutes != null }?.dayOffset

    /** Null when [currentMinutes] is null (a future day with no current-week data yet).
     *  [percentChange] is null when [previousMinutes] is zero (undefined percent change),
     *  matching [app.readylytics.health.core.scoring.domain.workouts.weekly.PeriodComparison]'s
     *  existing null-percent convention. */
    fun dailyDelta(
        currentMinutes: Int?,
        previousMinutes: Int,
    ): DailyDeltaResult? {
        if (currentMinutes == null) return null
        val deltaMinutes = currentMinutes - previousMinutes
        val percentChange =
            if (previousMinutes == 0) {
                null
            } else {
                (deltaMinutes.toFloat() / previousMinutes.toFloat() * PERCENT_MULTIPLIER)
            }
        return DailyDeltaResult(deltaMinutes, percentChange)
    }

    private const val PERCENT_MULTIPLIER = 100f
}
