package app.readylytics.health.feature.vitals.heartrate

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.readylytics.health.core.ui.components.DataPointTooltip
import app.readylytics.health.core.ui.components.DataPointTooltipData
import app.readylytics.health.core.ui.components.DayTimelineScale
import app.readylytics.health.core.ui.components.EmptyChartPlaceholder
import app.readylytics.health.core.ui.components.hrZoneColors
import app.readylytics.health.core.ui.model.HrSample
import app.readylytics.health.feature.vitals.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt
import app.readylytics.health.core.ui.R as CoreUiR

private const val GAP_THRESHOLD_MS = 10 * 60 * 1000L // 10 minutes
private val hourFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

object HrChartHelper {
    fun splitIntoSegments(
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

    fun generateHourLabels(
        dayStartMs: Long,
        endExclusiveMs: Long,
        zoneId: ZoneId,
    ): List<Pair<Long, String>> {
        val formatter = DateTimeFormatter.ofPattern("HH:mm")
        val labels = mutableListOf<Pair<Long, String>>()
        val startZdt = Instant.ofEpochMilli(dayStartMs).atZone(zoneId)
        var currentZdt = startZdt
        while (currentZdt.toInstant().toEpochMilli() < endExclusiveMs) {
            val hour = currentZdt.hour
            if (hour % 4 == 0) {
                labels.add(currentZdt.toInstant().toEpochMilli() to currentZdt.format(formatter))
            }
            currentZdt = currentZdt.plusHours(1)
        }
        return labels
    }
}

@Composable
fun HrTimelineChart(
    samples: List<HrSample>,
    dayStartMs: Long,
    dayEndMs: Long,
    zone1MinBpm: Int,
    zone1MaxBpm: Int,
    zone2MaxBpm: Int,
    zone3MaxBpm: Int,
    zone4MaxBpm: Int,
    modifier: Modifier = Modifier,
    zoneId: ZoneId = ZoneId.systemDefault(),
) {
    if (samples.isEmpty()) {
        EmptyChartPlaceholder(modifier = modifier)
    } else {
        HrTimelineChartContent(
            samples = samples,
            dayStartMs = dayStartMs,
            dayEndMs = dayEndMs,
            zone1MinBpm = zone1MinBpm,
            zone1MaxBpm = zone1MaxBpm,
            zone2MaxBpm = zone2MaxBpm,
            zone3MaxBpm = zone3MaxBpm,
            zone4MaxBpm = zone4MaxBpm,
            modifier = modifier,
            zoneId = zoneId,
        )
    }
}

@Composable
private fun HrTimelineChartContent(
    samples: List<HrSample>,
    dayStartMs: Long,
    dayEndMs: Long,
    zone1MinBpm: Int,
    zone1MaxBpm: Int,
    zone2MaxBpm: Int,
    zone3MaxBpm: Int,
    zone4MaxBpm: Int,
    modifier: Modifier = Modifier,
    zoneId: ZoneId = ZoneId.systemDefault(),
) {
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

    val scale = remember(dayStartMs, dayEndMs) { DayTimelineScale(dayStartMs, dayEndMs) }

    var scaleX by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var selectedSample by remember { mutableStateOf<HrSample?>(null) }

    // Clear selected sample on date/data changes
    LaunchedEffect(dayStartMs, samples) {
        if (selectedSample != null && samples.none { it.timeMs == selectedSample?.timeMs }) {
            selectedSample = null
        }
    }

    // Reset zoom/pan on date change
    LaunchedEffect(dayStartMs) {
        scaleX = 1f
        offsetX = 0f
    }

    val segments = remember(samples) { HrChartHelper.splitIntoSegments(samples, GAP_THRESHOLD_MS) }
    val hourLabels =
        remember(dayStartMs, dayEndMs, zoneId) {
            HrChartHelper.generateHourLabels(dayStartMs, dayEndMs, zoneId)
        }
    val yLabels =
        remember(zone1MinBpm, zone1MaxBpm, zone2MaxBpm, zone3MaxBpm, zone4MaxBpm) {
            listOf(zone1MinBpm, zone1MaxBpm, zone2MaxBpm, zone3MaxBpm, zone4MaxBpm)
        }

    BoxWithConstraints(modifier = modifier) {
        val chartWidthPx = with(density) { maxWidth.toPx() }
        val leftLabelWidthPx = with(density) { 36.dp.toPx() }
        val plotW = chartWidthPx - leftLabelWidthPx

        fun timestampToX(timestampMs: Long): Float {
            val frac = scale.fraction(timestampMs)
            return leftLabelWidthPx + frac * plotW
        }

        fun zoomedX(timestampMs: Long): Float =
            leftLabelWidthPx + (timestampToX(timestampMs) - leftLabelWidthPx) * scaleX + offsetX

        val tooltipState =
            remember(selectedSample, scaleX, offsetX, plotW, scale, yMin, yMax, zoneId) {
                val sample = selectedSample ?: return@remember null
                val bottomLabelHeightPx = with(density) { 20.dp.toPx() }
                val canvasHeightPx = with(density) { 220.dp.toPx() }
                val plotTop = 0f
                val plotBottom = canvasHeightPx - bottomLabelHeightPx
                val plotH = plotBottom - plotTop

                val sampleX = zoomedX(sample.timeMs)
                val sampleY = plotTop + (1f - (sample.bpm - yMin).toFloat() / (yMax - yMin).toFloat()) * plotH

                val timeStr =
                    Instant
                        .ofEpochMilli(sample.timeMs)
                        .atZone(zoneId)
                        .format(hourFormatter)

                DataPointTooltipData(
                    valueText = "${sample.bpm} bpm",
                    dateText = timeStr,
                    offset = IntOffset(sampleX.roundToInt(), sampleY.roundToInt()),
                )
            }
        val prevActionLabel = stringResource(R.string.action_previous_point)
        val nextActionLabel = stringResource(R.string.action_next_point)
        val clearActionLabel = stringResource(R.string.action_clear_selection)

        val customActionsList =
            remember(selectedSample, samples) {
                val list = mutableListOf<CustomAccessibilityAction>()
                if (samples.isNotEmpty()) {
                    list.add(
                        CustomAccessibilityAction(prevActionLabel) {
                            val currentIndex = samples.indexOfFirst { it.timeMs == selectedSample?.timeMs }
                            selectedSample =
                                if (currentIndex > 0) {
                                    samples[currentIndex - 1]
                                } else {
                                    samples.last()
                                }
                            true
                        },
                    )
                    list.add(
                        CustomAccessibilityAction(nextActionLabel) {
                            val currentIndex = samples.indexOfFirst { it.timeMs == selectedSample?.timeMs }
                            selectedSample =
                                if (currentIndex != -1 && currentIndex < samples.lastIndex) {
                                    samples[currentIndex + 1]
                                } else {
                                    samples.first()
                                }
                            true
                        },
                    )
                }
                if (selectedSample != null) {
                    list.add(
                        CustomAccessibilityAction(clearActionLabel) {
                            selectedSample = null
                            true
                        },
                    )
                }
                list
            }

        val chartSummary = stringResource(CoreUiR.string.chart_accessibility_rhr_summary)
        val selectedValueDescription =
            selectedSample?.let { sample ->
                val timeStr = Instant.ofEpochMilli(sample.timeMs).atZone(zoneId).format(hourFormatter)
                stringResource(CoreUiR.string.chart_accessibility_selected_rhr, sample.bpm, timeStr)
            } ?: stringResource(CoreUiR.string.chart_accessibility_no_selection)

        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .testTag("HrTimelineChartCanvas")
                    .semantics {
                        contentDescription = chartSummary
                        stateDescription = selectedValueDescription
                        customActions = customActionsList
                    }.pointerInput(Unit) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            scaleX = (scaleX * zoom).coerceIn(1f, 5f)
                            val maxOffset = (scaleX - 1f) * plotW
                            offsetX = (offsetX + pan.x).coerceIn(-maxOffset, 0f)
                        }
                    }.pointerInput(samples, dayStartMs, scaleX, offsetX, scale) {
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

                            val tapFrac = ((tappedUnscaledX - plotLeft) / plotW).coerceIn(0f, 1f)
                            val tapMs = dayStartMs + (tapFrac * scale.durationMs).toLong()

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
            for ((ts, _) in hourLabels) {
                val x = zoomedX(ts)
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
            for ((ts, label) in hourLabels) {
                val x = zoomedX(ts)
                if (x in plotLeft..plotRight) {
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
                        val x = zoomedX(segment[0].timeMs)
                        drawCircle(
                            color = lineColor,
                            radius = 3.dp.toPx(),
                            center = Offset(x, bpmToY(segment[0].bpm)),
                        )
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
                val selectedX = zoomedX(selectedSample!!.timeMs)
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

// HrChartHelper handles splitIntoSegments now

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
