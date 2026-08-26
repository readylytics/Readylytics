package app.readylytics.health.feature.workouts

/**
 * Holds the resolved data for the currently tapped day on the "Training time comparison"
 * cumulative volume chart.
 *
 * @param dayOffset       Index into the chart's fixed 7-day x-axis (0 = configured week start).
 * @param currentMinutes  Current-week cumulative minutes at [dayOffset]; null on a day strictly
 *                        after today, where the current-week line has no point.
 * @param previousMinutes Previous-week cumulative minutes at [dayOffset]; always present.
 * @param canvasX         Canvas x-coordinate of the selected day, for the tooltip/overlay anchor.
 * @param canvasY         Canvas y-coordinate of whichever series has a point at [dayOffset].
 */
internal data class WeeklyVolumeSelectedState(
    val dayOffset: Int,
    val currentMinutes: Int?,
    val previousMinutes: Int,
    val canvasX: Float,
    val canvasY: Float?,
)
