package com.gregor.lauritz.healthdashboard.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gregor.lauritz.healthdashboard.ui.heartrate.HrSample
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

private const val GAP_THRESHOLD_MS = 10 * 60 * 1000L // 10 minutes
private val hourFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val HOUR_LABELS = listOf(0, 4, 8, 12, 16, 20)

@Composable
fun HrTimelineChart(
    samples: List<HrSample>,
    dayStartMs: Long,
    zone1MinBpm: Int,
    zone1MaxBpm: Int,
    zone2MaxBpm: Int,
    zone3MaxBpm: Int,
    zone4MaxBpm: Int,
    modifier: Modifier = Modifier,
) {
    if (samples.isEmpty()) {
        EmptyChartPlaceholder(modifier = modifier)
        return
    }

    val zone0Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val zone1Color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f)
    val zone2Color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
    val zone3Color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
    val zone4Color = MaterialTheme.colorScheme.error.copy(alpha = 0.25f)
    val zone5Color = MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
    val lineColor = MaterialTheme.colorScheme.primary
    val axisTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    val axisLineColor = MaterialTheme.colorScheme.outlineVariant

    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelStyle = TextStyle(color = axisTextColor, fontSize = 10.sp)

    val yMin =
        remember(samples, zone1MinBpm) {
            (minOf(samples.minOf { it.bpm }, zone1MinBpm) - 10).coerceAtLeast(30)
        }
    val yMax =
        remember(samples, zone4MaxBpm) {
            maxOf(samples.maxOf { it.bpm }, zone4MaxBpm) + 10
        }

    var tooltipState by remember { mutableStateOf<DataPointTooltipData?>(null) }

    val segments = remember(samples) { splitIntoSegments(samples, GAP_THRESHOLD_MS) }
    val yLabels =
        remember(zone1MinBpm, zone1MaxBpm, zone2MaxBpm, zone3MaxBpm, zone4MaxBpm) {
            listOf(zone1MinBpm, zone1MaxBpm, zone2MaxBpm, zone3MaxBpm, zone4MaxBpm)
        }

    Box(modifier = modifier) {
        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .pointerInput(samples, dayStartMs) {
                        detectTapGestures { tapOffset ->
                            val leftLabelWidthPx = 36.dp.toPx()
                            val bottomLabelHeightPx = 20.dp.toPx()
                            val plotLeft = leftLabelWidthPx
                            val plotTop = 0f
                            val plotRight = size.width.toFloat()
                            val plotBottom = size.height.toFloat() - bottomLabelHeightPx
                            val plotRect = Rect(plotLeft, plotTop, plotRight, plotBottom)
                            if (!plotRect.contains(tapOffset)) return@detectTapGestures

                            val tapMinuteOfDay =
                                ((tapOffset.x - plotRect.left) / plotRect.width * 1440f)
                                    .toInt()
                                    .coerceIn(0, 1439)
                            val tapMs = dayStartMs + tapMinuteOfDay * 60_000L

                            val nearest =
                                samples.minByOrNull { abs(it.timeMs - tapMs) } ?: return@detectTapGestures
                            val nearestMinute = ((nearest.timeMs - dayStartMs) / 60_000L).toInt()
                            val sampleX = plotRect.left + nearestMinute / 1440f * plotRect.width
                            val sampleY =
                                plotRect.top +
                                    (1f - (nearest.bpm - yMin).toFloat() / (yMax - yMin).toFloat()) *
                                    plotRect.height

                            val timeStr =
                                Instant
                                    .ofEpochMilli(nearest.timeMs)
                                    .atZone(ZoneId.systemDefault())
                                    .format(hourFormatter)

                            tooltipState =
                                DataPointTooltipData(
                                    valueText = "${nearest.bpm} bpm",
                                    dateText = timeStr,
                                    offset = IntOffset(sampleX.roundToInt(), sampleY.roundToInt()),
                                )
                        }
                    },
        ) {
            val leftLabelWidth = with(density) { 36.dp.toPx() }
            val bottomLabelHeight = with(density) { 20.dp.toPx() }
            val plotLeft = leftLabelWidth
            val plotTop = 0f
            val plotRight = size.width
            val plotBottom = size.height - bottomLabelHeight
            val plotW = plotRight - plotLeft
            val plotH = plotBottom - plotTop
            val plotRect = Rect(plotLeft, plotTop, plotRight, plotBottom)

            fun bpmToY(bpm: Int): Float = plotTop + (1f - (bpm - yMin).toFloat() / (yMax - yMin).toFloat()) * plotH

            fun minuteToX(minute: Int): Float = plotLeft + minute / 1440f * plotW

            // Draw zone bands
            drawZoneBand(zone5Color, bpmToY(zone4MaxBpm), plotTop, plotLeft, plotW)
            drawZoneBand(zone4Color, bpmToY(zone3MaxBpm), bpmToY(zone4MaxBpm), plotLeft, plotW)
            drawZoneBand(zone3Color, bpmToY(zone2MaxBpm), bpmToY(zone3MaxBpm), plotLeft, plotW)
            drawZoneBand(zone2Color, bpmToY(zone1MaxBpm), bpmToY(zone2MaxBpm), plotLeft, plotW)
            drawZoneBand(zone1Color, bpmToY(zone1MinBpm), bpmToY(zone1MaxBpm), plotLeft, plotW)
            drawZoneBand(zone0Color, plotBottom, bpmToY(zone1MinBpm), plotLeft, plotW)

            // Draw horizontal axis line
            drawLine(
                color = axisLineColor,
                start = Offset(plotLeft, plotBottom),
                end = Offset(plotRight, plotBottom),
                strokeWidth = 1.dp.toPx(),
            )

            // Draw y-axis labels (zone boundaries) — yLabels is remembered in outer scope
            for (bpm in yLabels) {
                val y = bpmToY(bpm)
                if (y < plotBottom - 4.dp.toPx() && y > plotTop + 4.dp.toPx()) {
                    val measured = textMeasurer.measure(bpm.toString(), labelStyle)
                    drawText(
                        textLayoutResult = measured,
                        topLeft =
                            Offset(
                                x = plotLeft - measured.size.width - 4.dp.toPx(),
                                y = y - measured.size.height / 2f,
                            ),
                    )
                }
            }

            // Draw x-axis hour labels (every 4 hours)
            for (hour in HOUR_LABELS) {
                val x = minuteToX(hour * 60)
                val label = "%02d:00".format(hour)
                val measured = textMeasurer.measure(label, labelStyle)
                drawText(
                    textLayoutResult = measured,
                    topLeft =
                        Offset(
                            x = (x - measured.size.width / 2f).coerceIn(plotLeft, plotRight - measured.size.width),
                            y = plotBottom + 2.dp.toPx(),
                        ),
                )
            }

            // Draw HR line with gap breaks — segments is remembered in outer scope
            for (segment in segments) {
                if (segment.size == 1) {
                    val minute = ((segment[0].timeMs - dayStartMs) / 60_000L).toInt().coerceIn(0, 1439)
                    drawCircle(
                        color = lineColor,
                        radius = 3.dp.toPx(),
                        center = Offset(minuteToX(minute), bpmToY(segment[0].bpm)),
                    )
                } else {
                    val path = Path()
                    segment.forEachIndexed { i, sample ->
                        val minute = ((sample.timeMs - dayStartMs) / 60_000L).toInt().coerceIn(0, 1439)
                        val x = minuteToX(minute)
                        val y = bpmToY(sample.bpm)
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(
                        path = path,
                        color = lineColor,
                        style =
                            Stroke(
                                width = 2.dp.toPx(),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round,
                            ),
                    )
                }
            }
        }

        if (tooltipState != null) {
            DataPointTooltip(
                isVisible = true,
                data = tooltipState!!,
                onDismissRequest = { tooltipState = null },
            )
        }
    }
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

private fun splitIntoSegments(
    samples: List<HrSample>,
    gapThresholdMs: Long,
): List<List<HrSample>> {
    if (samples.isEmpty()) return emptyList()
    val segments = mutableListOf<MutableList<HrSample>>()
    var current = mutableListOf(samples[0])
    for (i in 1 until samples.size) {
        if (samples[i].timeMs - samples[i - 1].timeMs > gapThresholdMs) {
            segments.add(current)
            current = mutableListOf(samples[i])
        } else {
            current.add(samples[i])
        }
    }
    segments.add(current)
    return segments
}

@Composable
fun HrSparkline(
    hourlySamples: List<Pair<Int, Int>>,
    modifier: Modifier = Modifier,
) {
    if (hourlySamples.isEmpty()) return
    val lineColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
    val minBpm = hourlySamples.minOf { it.second }
    val maxBpm = hourlySamples.maxOf { it.second }
    val range = (maxBpm - minBpm).coerceAtLeast(10)

    Canvas(modifier = modifier.padding(vertical = 2.dp)) {
        val path = Path()
        hourlySamples.forEachIndexed { i, (hour, bpm) ->
            val x = hour / 23f * size.width
            val y = (1f - (bpm - minBpm).toFloat() / range.toFloat()) * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
