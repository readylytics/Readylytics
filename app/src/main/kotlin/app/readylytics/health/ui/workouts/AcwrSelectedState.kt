package app.readylytics.health.ui.workouts

/**
 * Holds all data needed to render the interactive selection overlay and tooltip
 * for the ACWR (Training Load) chart.
 *
 * @param dayOffset      Index into the chart's x-axis (day number from range start).
 * @param trimpValue     Daily TRIMP value for the selected day; null if no data.
 * @param strainRatioValue Strain Ratio (ACWR) for the selected day; null if no data.
 * @param canvasX        Canvas x-coordinate of the selected column / dot.
 * @param barCanvasYTop  Canvas y-coordinate of the top of the selected TRIMP bar;
 *                       null if the column layer was not hit by the touch event.
 * @param lineCanvasY    Canvas y-coordinate of the Strain Ratio dot;
 *                       null if the line layer was not hit by the touch event.
 */
data class AcwrSelectedState(
    val dayOffset: Int,
    val trimpValue: Float?,
    val strainRatioValue: Float?,
    val canvasX: Float,
    val barCanvasYTop: Float?,
    val lineCanvasY: Float?,
)
