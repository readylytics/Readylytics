package com.gregor.lauritz.healthdashboard.ui.sleep

/**
 * Selection state for the Sleep Trend Chart.
 *
 * @param dayOffset            The day index relative to the range start.
 * @param startOffsetValue     The sleep start offset (hours since Noon).
 * @param durationSpanValue    The sleep window duration in hours (time in bed).
 * @param actualDurationValue  The actual sleep duration in hours (excluding awake time).
 * @param canvasX              The canvas x-coordinate of the selected column/point.
 * @param barCanvasYTop        The canvas y-coordinate of the top of the visible bar (wake-up time).
 * @param barCanvasYBottom     The canvas y-coordinate of the bottom of the visible bar (bedtime).
 * @param lineCanvasY          The canvas y-coordinate of the line node (actual sleep duration).
 */
data class SleepTrendSelectedState(
    val dayOffset: Int,
    val startOffsetValue: Float?,
    val durationSpanValue: Float?,
    val actualDurationValue: Float?,
    val canvasX: Float,
    val barCanvasYTop: Float?,
    val barCanvasYBottom: Float?,
    val lineCanvasY: Float?,
)
