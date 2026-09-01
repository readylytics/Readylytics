package app.readylytics.health.feature.sleep

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.model.domain.repository.HeartRateRecordData
import java.time.Instant

// See the file-header comment in SleepHrChart.kt: the Canvas draw passes extracted out of
// SleepHrChart so it clears detekt's LongMethod/CyclomaticComplexMethod/TooManyFunctions
// thresholds without changing any behavior -- these are line-for-line ports of the original
// DrawScope block, only regrouped by draw pass (grid/axes, HR line, selection highlight).

internal fun DrawScope.renderSleepHrCanvas(
    data: SleepHrDerivedData,
    style: SleepHrChartStyle,
    selectedSample: HeartRateRecordData?,
    pulse: SleepHrPulseAnimation,
    leftLabelWidthPx: Float,
    zoomedX: (Long) -> Float,
) {
    val plotTop = 0f
    val bottomLabelHeight = SLEEP_HR_BOTTOM_LABEL_HEIGHT.toPx()
    val plotRect = Rect(leftLabelWidthPx, plotTop, size.width, size.height - bottomLabelHeight)
    val plotH = plotRect.bottom - plotRect.top

    fun bpmToY(bpm: Int): Float =
        plotRect.top + (1f - (bpm - data.yMin).toFloat() / (data.yMax - data.yMin).toFloat()) * plotH

    drawSleepHrGridAndAxes(plotRect, style, data.yLabels, data.labelTimestamps, ::bpmToY, zoomedX)
    drawSleepHrLine(plotRect, data.segments, style.lineColor, ::bpmToY, zoomedX)
    drawSleepHrSelection(plotRect, selectedSample, style.lineColor, pulse, ::bpmToY, zoomedX)
}

private fun DrawScope.drawSleepHrGridAndAxes(
    plotRect: Rect,
    style: SleepHrChartStyle,
    yLabels: List<Int>,
    labelTimestamps: List<Long>,
    bpmToY: (Int) -> Float,
    zoomedX: (Long) -> Float,
) {
    val gridLineColor = style.axisLineColor.copy(alpha = 0.4f)
    val strokePx = 1.dp.toPx()

    for (bpm in yLabels) {
        val y = bpmToY(bpm)
        if (y < plotRect.bottom && y > plotRect.top) {
            drawLine(gridLineColor, Offset(plotRect.left, y), Offset(plotRect.right, y), strokePx)
        }
    }

    for (ts in labelTimestamps) {
        val x = zoomedX(ts)
        if (x in plotRect.left..plotRect.right) {
            drawLine(gridLineColor, Offset(x, plotRect.top), Offset(x, plotRect.bottom), strokePx)
        }
    }

    drawLine(
        style.axisLineColor,
        Offset(plotRect.left, plotRect.bottom),
        Offset(plotRect.right, plotRect.bottom),
        1.dp.toPx(),
    )

    drawSleepHrYAxisLabels(plotRect, style, yLabels, bpmToY)
    drawSleepHrXAxisLabels(plotRect, style, labelTimestamps, zoomedX)
}

private fun DrawScope.drawSleepHrYAxisLabels(
    plotRect: Rect,
    style: SleepHrChartStyle,
    yLabels: List<Int>,
    bpmToY: (Int) -> Float,
) {
    for (bpm in yLabels) {
        val y = bpmToY(bpm)
        if (y < plotRect.bottom - 4.dp.toPx() && y > plotRect.top + 4.dp.toPx()) {
            val measured = style.textMeasurer.measure(bpm.toString(), style.labelStyle)
            drawText(
                textLayoutResult = measured,
                topLeft = Offset(plotRect.left - measured.size.width - 4.dp.toPx(), y - measured.size.height / 2f),
            )
        }
    }

    val bpmUnitMeasured = style.textMeasurer.measure(style.bpmUnitLabel, style.axisTitleStyle)
    val bpmUnitPivot = Offset(x = 10.dp.toPx(), y = (plotRect.top + plotRect.bottom) / 2f)
    rotate(degrees = -90f, pivot = bpmUnitPivot) {
        drawText(
            textLayoutResult = bpmUnitMeasured,
            topLeft =
                Offset(
                    bpmUnitPivot.x - bpmUnitMeasured.size.width / 2f,
                    bpmUnitPivot.y - bpmUnitMeasured.size.height / 2f,
                ),
        )
    }
}

private fun DrawScope.drawSleepHrXAxisLabels(
    plotRect: Rect,
    style: SleepHrChartStyle,
    labelTimestamps: List<Long>,
    zoomedX: (Long) -> Float,
) {
    for (ts in labelTimestamps) {
        val x = zoomedX(ts)
        if (x in plotRect.left..plotRect.right) {
            val label = style.timeFormatter.format(Instant.ofEpochMilli(ts))
            val measured = style.textMeasurer.measure(label, style.labelStyle)
            drawText(
                textLayoutResult = measured,
                topLeft =
                    Offset(
                        (x - measured.size.width / 2f).coerceIn(plotRect.left, plotRect.right - measured.size.width),
                        plotRect.bottom + 2.dp.toPx(),
                    ),
            )
        }
    }
}

private fun DrawScope.drawSleepHrLine(
    plotRect: Rect,
    segments: List<List<HeartRateRecordData>>,
    lineColor: Color,
    bpmToY: (Int) -> Float,
    zoomedX: (Long) -> Float,
) {
    clipRect(left = plotRect.left, top = plotRect.top, right = plotRect.right, bottom = plotRect.bottom) {
        for (segment in segments) {
            if (segment.size == 1) {
                val x = zoomedX(segment[0].timestampMs)
                drawCircle(
                    color = lineColor,
                    radius = 3.dp.toPx(),
                    center = Offset(x, bpmToY(segment[0].beatsPerMinute)),
                )
            } else {
                val path = Path()
                segment.forEachIndexed { i, sample ->
                    val x = zoomedX(sample.timestampMs)
                    val y = bpmToY(sample.beatsPerMinute)
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

private fun DrawScope.drawSleepHrSelection(
    plotRect: Rect,
    selectedSample: HeartRateRecordData?,
    lineColor: Color,
    pulse: SleepHrPulseAnimation,
    bpmToY: (Int) -> Float,
    zoomedX: (Long) -> Float,
) {
    val selected = selectedSample ?: return
    val selectedX = zoomedX(selected.timestampMs)
    val selectedY = bpmToY(selected.beatsPerMinute)
    if (selectedX !in plotRect.left..plotRect.right) return

    drawLine(
        color = lineColor.copy(alpha = 0.4f),
        start = Offset(selectedX, plotRect.top),
        end = Offset(selectedX, plotRect.bottom),
        strokeWidth = 1.5.dp.toPx(),
    )
    drawCircle(
        color = lineColor.copy(alpha = pulse.alpha.value),
        radius = 8.dp.toPx() * pulse.radiusCoeff.value,
        center = Offset(selectedX, selectedY),
    )
    drawCircle(color = lineColor, radius = 4.dp.toPx(), center = Offset(selectedX, selectedY))
    drawCircle(
        color = Color.White,
        radius = 1.5.dp.toPx(),
        center = Offset(selectedX, selectedY),
    )
}
