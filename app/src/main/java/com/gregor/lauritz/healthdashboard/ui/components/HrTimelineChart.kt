package com.gregor.lauritz.healthdashboard.ui.components

import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.graphics.drawscope.clipRect
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

    // Pulsing animation for selected point highlight
    val infiniteTransition = rememberInfiniteTransition(label = "hrPulseTransition")
    val pulseRadiusCoeff by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.6f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(1200, easing = EaseInOutSine),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "hrPulseRadiusCoeff",
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.4f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(1200, easing = EaseInOutSine),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "hrPulseAlpha",
    )

    val zoneColors = hrZoneColors()
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

    var scaleX by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var selectedSample by remember { mutableStateOf<HrSample?>(null) }

    val segments = remember(samples) { splitIntoSegments(samples, GAP_THRESHOLD_MS) }
    val yLabels =
        remember(zone1MinBpm, zone1MaxBpm, zone2MaxBpm, zone3MaxBpm, zone4MaxBpm) {
            listOf(zone1MinBpm, zone1MaxBpm, zone2MaxBpm, zone3MaxBpm, zone4MaxBpm)
        }

    BoxWithConstraints(modifier = modifier) {
        val chartWidthPx = with(density) { maxWidth.toPx() }
        val leftLabelWidthPx = with(density) { 36.dp.toPx() }
        val plotW = chartWidthPx - leftLabelWidthPx

        fun minuteToX(minute: Int): Float = leftLabelWidthPx + minute / 1440f * plotW

        fun zoomedX(minute: Int): Float = leftLabelWidthPx + (minuteToX(minute) - leftLabelWidthPx) * scaleX + offsetX

        val tooltipState =
            remember(selectedSample, scaleX, offsetX, plotW) {
                val sample = selectedSample ?: return@remember null
                val nearestMinute = ((sample.timeMs - dayStartMs) / 60_000L).toInt()
                val bottomLabelHeightPx = with(density) { 20.dp.toPx() }
                val canvasHeightPx = with(density) { 220.dp.toPx() }
                val plotLeft = leftLabelWidthPx
                val plotTop = 0f
                val plotBottom = canvasHeightPx - bottomLabelHeightPx
                val plotH = plotBottom - plotTop

                val sampleX = zoomedX(nearestMinute)
                val sampleY = plotTop + (1f - (sample.bpm - yMin).toFloat() / (yMax - yMin).toFloat()) * plotH

                val timeStr =
                    Instant
                        .ofEpochMilli(sample.timeMs)
                        .atZone(ZoneId.systemDefault())
                        .format(hourFormatter)

                DataPointTooltipData(
                    valueText = "${sample.bpm} bpm",
                    dateText = timeStr,
                    offset = IntOffset(sampleX.roundToInt(), sampleY.roundToInt()),
                )
            }
        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .pointerInput(Unit) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            scaleX = (scaleX * zoom).coerceIn(1f, 5f)
                            val maxOffset = (scaleX - 1f) * plotW
                            offsetX = (offsetX + pan.x).coerceIn(-maxOffset, 0f)
                        }
                    }.pointerInput(samples, dayStartMs, scaleX, offsetX) {
                        detectTapGestures { tapOffset ->
                            val tappedZoomedX = tapOffset.x
                            val tappedUnscaledX =
                                leftLabelWidthPx + (tappedZoomedX - leftLabelWidthPx - offsetX) / scaleX

                            val bottomLabelHeightPx = 20.dp.toPx()
                            val plotLeft = leftLabelWidthPx
                            val plotTop = 0f
                            val plotRight = size.width.toFloat()
                            val plotBottom = size.height.toFloat() - bottomLabelHeightPx
                            val plotRect = Rect(plotLeft, plotTop, plotRight, plotBottom)
                            if (!plotRect.contains(tapOffset)) return@detectTapGestures

                            val tapMinuteOfDay =
                                ((tappedUnscaledX - plotLeft) / plotW * 1440f)
                                    .toInt()
                                    .coerceIn(0, 1439)
                            val tapMs = dayStartMs + tapMinuteOfDay * 60_000L

                            val nearest =
                                samples.minByOrNull { abs(it.timeMs - tapMs) } ?: return@detectTapGestures
                            selectedSample = nearest
                        }
                    },
        ) {
            val leftLabelWidth = leftLabelWidthPx
            val bottomLabelHeight = with(density) { 20.dp.toPx() }
            val plotLeft = leftLabelWidth
            val plotTop = 0f
            val plotRight = size.width
            val plotBottom = size.height - bottomLabelHeight
            val plotH = plotBottom - plotTop
            val plotRect = Rect(plotLeft, plotTop, plotRight, plotBottom)

            fun bpmToY(bpm: Int): Float = plotTop + (1f - (bpm - yMin).toFloat() / (yMax - yMin).toFloat()) * plotH

            // Draw zone bands (physical top y is smaller than physical bottom y)
            drawZoneBand(zoneColors.zone5, plotTop, bpmToY(zone4MaxBpm), plotLeft, plotW)
            drawZoneBand(zoneColors.zone4, bpmToY(zone4MaxBpm), bpmToY(zone3MaxBpm), plotLeft, plotW)
            drawZoneBand(zoneColors.zone3, bpmToY(zone3MaxBpm), bpmToY(zone2MaxBpm), plotLeft, plotW)
            drawZoneBand(zoneColors.zone2, bpmToY(zone2MaxBpm), bpmToY(zone1MaxBpm), plotLeft, plotW)
            drawZoneBand(zoneColors.zone1, bpmToY(zone1MaxBpm), bpmToY(zone1MinBpm), plotLeft, plotW)
            drawZoneBand(zoneColors.zone0, bpmToY(zone1MinBpm), plotBottom, plotLeft, plotW)

            // Draw grid lines
            val gridLineColor = axisLineColor.copy(alpha = 0.4f)
            val strokePx = 1.dp.toPx()

            // Horizontal grid lines at zone boundaries
            for (bpm in yLabels) {
                val y = bpmToY(bpm)
                if (y < plotBottom && y > plotTop) {
                    drawLine(
                        color = gridLineColor,
                        start = Offset(plotLeft, y),
                        end = Offset(plotRight, y),
                        strokeWidth = strokePx,
                    )
                }
            }

            // Vertical grid lines at hour labels (zoomed and panned)
            for (hour in HOUR_LABELS) {
                val x = zoomedX(hour * 60)
                if (x in plotLeft..plotRight) {
                    drawLine(
                        color = gridLineColor,
                        start = Offset(x, plotTop),
                        end = Offset(x, plotBottom),
                        strokeWidth = strokePx,
                    )
                }
            }

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

            // Draw x-axis hour labels (every 4 hours, zoomed and panned)
            for (hour in HOUR_LABELS) {
                val x = zoomedX(hour * 60)
                if (x in plotLeft..plotRight) {
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
            }

            // Draw HR line with gap breaks — segments is remembered in outer scope (clipped to graph bounds)
            clipRect(left = plotLeft, top = plotTop, right = plotRight, bottom = plotBottom) {
                for (segment in segments) {
                    if (segment.size == 1) {
                        val minute = ((segment[0].timeMs - dayStartMs) / 60_000L).toInt().coerceIn(0, 1439)
                        val x = zoomedX(minute)
                        drawCircle(
                            color = lineColor,
                            radius = 3.dp.toPx(),
                            center = Offset(x, bpmToY(segment[0].bpm)),
                        )
                    } else {
                        val path = Path()
                        segment.forEachIndexed { i, sample ->
                            val minute = ((sample.timeMs - dayStartMs) / 60_000L).toInt().coerceIn(0, 1439)
                            val x = zoomedX(minute)
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

            // Draw vertical pointer line and pulsing selected point on tap
            if (selectedSample != null) {
                val nearestMinute = ((selectedSample!!.timeMs - dayStartMs) / 60_000L).toInt()
                val selectedX = zoomedX(nearestMinute)
                val selectedY = bpmToY(selectedSample!!.bpm)

                if (selectedX in plotLeft..plotRight) {
                    // Draw vertical indicator line
                    drawLine(
                        color = lineColor.copy(alpha = 0.4f),
                        start = Offset(selectedX, plotTop),
                        end = Offset(selectedX, plotBottom),
                        strokeWidth = 1.5.dp.toPx(),
                    )

                    // Draw pulsing breathing outer glow/halo
                    drawCircle(
                        color = lineColor.copy(alpha = pulseAlpha),
                        radius = 8.dp.toPx() * pulseRadiusCoeff,
                        center = Offset(selectedX, selectedY),
                    )

                    // Draw solid inner point
                    drawCircle(
                        color = lineColor,
                        radius = 4.dp.toPx(),
                        center = Offset(selectedX, selectedY),
                    )

                    // Draw white core
                    drawCircle(
                        color = Color.White,
                        radius = 1.5.dp.toPx(),
                        center = Offset(selectedX, selectedY),
                    )
                }
            }
        }

        if (tooltipState != null) {
            DataPointTooltip(
                isVisible = true,
                data = tooltipState,
                yOffsetDp = (-28).dp,
                onDismissRequest = { selectedSample = null },
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
