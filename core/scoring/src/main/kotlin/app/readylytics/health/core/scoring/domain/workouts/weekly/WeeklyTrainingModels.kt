package app.readylytics.health.core.scoring.domain.workouts.weekly

import app.readylytics.health.core.model.domain.service.DateRange
import app.readylytics.health.core.model.domain.workouts.detail.WorkoutLayoutType
import java.time.LocalDate

/** Which raw quantity best represents weekly volume for a given [WorkoutLayoutType]. */
enum class ActivityMetricType { DISTANCE, DURATION }

/**
 * Single shared aggregation result feeding every weekly Workout-tab visualization: weekly
 * training totals, the this-week-vs-last-week daily cumulative chart, per-activity-type volume,
 * and training mix. Built once by [ComputeWeeklyTrainingStatsUseCase] so all four visualizations
 * agree on the same workout inclusion, duration, activity classification, and week boundaries.
 */
data class WeeklyTrainingStats(
    /** Configured week start through the caller's `today`, inclusive. Partial for an in-progress week. */
    val currentPeriod: DateRange,
    /** Full 7-day previous configured week, entirely in the past. */
    val previousPeriod: DateRange,
    val currentWeek: PeriodTotals,
    val previousWeek: PeriodTotals,
    val comparison: PeriodComparison,
    /** Exactly 7 entries, one per day of the configured week, ordered from the week start. */
    val cumulativeDailyTraining: List<DailyTrainingVolume>,
    val activityVolumes: List<ActivityVolume>,
    val trainingMix: List<TrainingMixItem>,
)

data class PeriodTotals(
    val totalDurationMinutes: Int,
    val workoutCount: Int,
    val activeDays: Int,
)

data class PeriodComparison(
    val durationDeltaMinutes: Int,
    /** Null when the previous period's total duration was zero (undefined percent change). */
    val durationPercentChange: Float?,
    val workoutCountDelta: Int,
    val activeDaysDelta: Int,
)

/**
 * One entry per day of the configured week (offset 0..6 from the week start). [date] is the
 * current week's calendar date for this offset; the previous week's date for the same offset is
 * implicitly `date.minusWeeks(1)`, not modeled as a separate field.
 *
 * `currentWeek*` fields are null only for days strictly after `today` — no fabricated future
 * values. `previousWeek*` fields are always present (zero on a rest day) since the previous week
 * is entirely in the past.
 */
data class DailyTrainingVolume(
    val dayOffset: Int,
    val date: LocalDate,
    val currentWeekDurationMinutes: Int?,
    val previousWeekDurationMinutes: Int,
    val currentWeekCumulativeMinutes: Int?,
    val previousWeekCumulativeMinutes: Int,
)

data class ActivityVolume(
    val activityType: WorkoutLayoutType,
    val metricType: ActivityMetricType,
    /** Meters when [metricType] is [ActivityMetricType.DISTANCE], minutes when [ActivityMetricType.DURATION]. */
    val currentWeekValue: Float,
    val previousWeekValue: Float,
    val absoluteChange: Float,
    /** Null when [previousWeekValue] is zero (undefined percent change). */
    val percentChange: Float?,
)

data class TrainingMixItem(
    val activityType: WorkoutLayoutType,
    val durationMinutes: Int,
    /** Percentage (0..100) of the current week's total training duration. */
    val percentage: Float,
)
