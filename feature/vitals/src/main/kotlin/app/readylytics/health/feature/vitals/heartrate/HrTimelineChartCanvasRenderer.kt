package app.readylytics.health.feature.vitals.heartrate

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.ui.model.HrSample

// See the file-header comment in HrTimelineChart.kt: the Canvas draw passes extracted out of
// HrTimelineChartContent so it clears detekt's LongMethod/CyclomaticComplexMethod/
// TooManyFunctions thresholds without changing any behavior -- these are line-for-line ports of
// the original DrawScope block, only regrouped by draw pass (zone bands, grid/axes, HR line,
// selection highlight).

internal fun DrawScope.renderHrTimelineCanvas(
    data: HrTimelineDerivedData,
    style: HrTimelineChartStyle,
    zoneBounds: HrZoneBounds,
    selectedSample: HrSample?,
    pulse: HrTimelinePulseAnimation,
    leftLabelWidthPx: Float,
    zoomedX: (Long) -> Float,
) {
    val bottomLabelHeight = HR_TIMELINE_BOTTOM_LABEL_HEIGHT.toPx()
    val plotRect = Rect(leftLabelWidthPx, 0f, size.width, size.height - bottomLabelHeight)
    val plotH = plotRect.bottom - plotRect.top

    fun bpmToY(bpm: Int): Float =
        plotRect.top + (1f - (bpm - data.yMin).toFloat() / (data.yMax - data.yMin).toFloat()) * plotH

    drawHrTimelineZoneBands(plotRect, style, zoneBounds, ::bpmToY)
    drawHrTimelineGridAndAxes(plotRect, style, data.yLabels, data.hourLabels, ::bpmToY, zoomedX)
    drawHrTimelineLine(plotRect, data.segments, style.lineColor, ::bpmToY, zoomedX)
    drawHrTimelineSelection(plotRect, selectedSample, style.lineColor, pulse, ::bpmToY, zoomedX)
}

private fun DrawScope.drawHrTimelineZoneBands(
    plotRect: Rect,
    style: HrTimelineChartStyle,
    zoneBounds: HrZoneBounds,
    bpmToY: (Int) -> Float,
) {
    val zoneColors = style.zoneColors

    // Draw zone bands (physical top y is smaller than physical bottom y)
    drawZoneBand(zoneColors.zone5, plotRect.top, bpmToY(zoneBounds.zone4MaxBpm), plotRect.left, plotRect.width)
    drawZoneBand(
        zoneColors.zone4,
        bpmToY(zoneBounds.zone4MaxBpm),
        bpmToY(zoneBounds.zone3MaxBpm),
        plotRect.left,
        plotRect.width,
    )
    drawZoneBand(
        zoneColors.zone3,
        bpmToY(zoneBounds.zone3MaxBpm),
        bpmToY(zoneBounds.zone2MaxBpm),
        plotRect.left,
        plotRect.width,
    )
    drawZoneBand(
        zoneColors.zone2,
        bpmToY(zoneBounds.zone2MaxBpm),
        bpmToY(zoneBounds.zone1MaxBpm),
        plotRect.left,
        plotRect.width,
    )
    drawZoneBand(
        zoneColors.zone1,
        bpmToY(zoneBounds.zone1MaxBpm),
        bpmToY(zoneBounds.zone1MinBpm),
        plotRect.left,
        plotRect.width,
    )
    drawZoneBand(zoneColors.zone0, bpmToY(zoneBounds.zone1MinBpm), plotRect.bottom, plotRect.left, plotRect.width)
}

private fun DrawScope.drawZoneBand(
    color: Color,
    top: Float,
    bottom: Float,
    left: Float,
    width: Float,
) {
    val clampedTop = top.coerceAtLeast(0f)
    val clampedBottom = bottom.coerceAtMost(size.height)
    if (clampedBottom > clampedTop) {
        drawRect(
            color = color,
            topLeft = Offset(left, clampedTop),
            size = Size(width, clampedBottom - clampedTop),
        )
    }
}

private fun DrawScope.drawHrTimelineGridAndAxes(
    plotRect: Rect,
    style: HrTimelineChartStyle,
    yLabels: List<Int>,
    hourLabels: List<Pair<Long, String>>,
    bpmToY: (Int) -> Float,
    zoomedX: (Long) -> Float,
) {
    val gridLineColor = style.axisLineColor.copy(alpha = 0.4f)
    val strokePx = 1.dp.toPx()

    // Horizontal grid lines at zone boundaries
    for (bpm in yLabels) {
        val y = bpmToY(bpm)
        if (y < plotRect.bottom && y > plotRect.top) {
            drawLine(
                color = gridLineColor,
                start = Offset(plotRect.left, y),
                end = Offset(plotRect.right, y),
                strokeWidth = strokePx,
            )
        }
    }

    // Vertical grid lines at hour labels (zoomed and panned)
    for ((ts, _) in hourLabels) {
        val x = zoomedX(ts)
        if (x in plotRect.left..plotRect.right) {
            drawLine(
                color = gridLineColor,
                start = Offset(x, plotRect.top),
                end = Offset(x, plotRect.bottom),
                strokeWidth = strokePx,
            )
        }
    }

    // Draw horizontal axis line
    drawLine(
        color = style.axisLineColor,
        start = Offset(plotRect.left, plotRect.bottom),
        end = Offset(plotRect.right, plotRect.bottom),
        strokeWidth = 1.dp.toPx(),
    )

    drawHrTimelineYAxisLabels(plotRect, style, yLabels, bpmToY)
    drawHrTimelineXAxisLabels(plotRect, style, hourLabels, zoomedX)
}

private fun DrawScope.drawHrTimelineYAxisLabels(
    plotRect: Rect,
    style: HrTimelineChartStyle,
    yLabels: List<Int>,
    bpmToY: (Int) -> Float,
) {
    // Draw y-axis labels (zone boundaries)
    for (bpm in yLabels) {
        val y = bpmToY(bpm)
        if (y < plotRect.bottom - 4.dp.toPx() && y > plotRect.top + 4.dp.toPx()) {
            val measured = style.textMeasurer.measure(bpm.toString(), style.labelStyle)
            drawText(
                textLayoutResult = measured,
                topLeft =
                    Offset(
                        x = plotRect.left - measured.size.width - 4.dp.toPx(),
                        y = y - measured.size.height / 2f,
                    ),
            )
        }
    }
}

private fun DrawScope.drawHrTimelineXAxisLabels(
    plotRect: Rect,
    style: HrTimelineChartStyle,
    hourLabels: List<Pair<Long, String>>,
    zoomedX: (Long) -> Float,
) {
    // Draw x-axis hour labels (every 4 hours, zoomed and panned)
    for ((ts, label) in hourLabels) {
        val x = zoomedX(ts)
        if (x in plotRect.left..plotRect.right) {
            val measured = style.textMeasurer.measure(label, style.labelStyle)
            drawText(
                textLayoutResult = measured,
                topLeft =
                    Offset(
                        x =
                            (x - measured.size.width / 2f).coerceIn(
                                plotRect.left,
                                plotRect.right - measured.size.width,
                            ),
                        y = plotRect.bottom + 2.dp.toPx(),
                    ),
            )
        }
    }
}

private fun DrawScope.drawHrTimelineLine(
    plotRect: Rect,
    segments: List<List<HrSample>>,
    lineColor: Color,
    bpmToY: (Int) -> Float,
    zoomedX: (Long) -> Float,
) {
    // Draw HR line with gap breaks (clipped to graph bounds)
    clipRect(left = plotRect.left, top = plotRect.top, right = plotRect.right, bottom = plotRect.bottom) {
        for (segment in segments) {
            if (segment.size == 1) {
                val x = zoomedX(segment[0].timeMs)
                drawCircle(color = lineColor, radius = 3.dp.toPx(), center = Offset(x, bpmToY(segment[0].bpm)))
            } else {
                val path = Path()
                segment.forEachIndexed { i, sample ->
                    val x = zoomedX(sample.timeMs)
                    val y = bpmToY(sample.bpm)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path = path,
                    color = lineColor,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
    }
}

private fun DrawScope.drawHrTimelineSelection(
    plotRect: Rect,
    selectedSample: HrSample?,
    lineColor: Color,
    pulse: HrTimelinePulseAnimation,
    bpmToY: (Int) -> Float,
    zoomedX: (Long) -> Float,
) {
    // Draw vertical pointer line and pulsing selected point on tap
    val selected = selectedSample ?: return
    val selectedX = zoomedX(selected.timeMs)
    val selectedY = bpmToY(selected.bpm)
    if (selectedX !in plotRect.left..plotRect.right) return

    // Draw vertical indicator line
    drawLine(
        color = lineColor.copy(alpha = 0.4f),
        start = Offset(selectedX, plotRect.top),
        end = Offset(selectedX, plotRect.bottom),
        strokeWidth = 1.5.dp.toPx(),
    )
    // Draw pulsing breathing outer glow/halo
    drawCircle(
        color = lineColor.copy(alpha = pulse.alpha.value),
        radius = 8.dp.toPx() * pulse.radiusCoeff.value,
        center = Offset(selectedX, selectedY),
    )
    // Draw solid inner point
    drawCircle(color = lineColor, radius = 4.dp.toPx(), center = Offset(selectedX, selectedY))
    // Draw white core
    drawCircle(color = Color.White, radius = 1.5.dp.toPx(), center = Offset(selectedX, selectedY))
}
