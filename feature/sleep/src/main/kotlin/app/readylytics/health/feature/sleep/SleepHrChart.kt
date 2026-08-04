package app.readylytics.health.feature.sleep

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
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
import app.readylytics.health.domain.repository.HeartRateRecordData
import app.readylytics.health.domain.repository.SleepSessionData
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import app.readylytics.health.core.ui.R as CoreUiR

internal const val SLEEP_HR_GAP_THRESHOLD_MS = 10 * 60 * 1000L // 10 minutes
private const val Y_TICK_COUNT = 4
private val LEFT_LABEL_WIDTH = 36.dp
private val BOTTOM_LABEL_HEIGHT = 20.dp
private val CHART_HEIGHT = 220.dp

internal object SleepHrChartHelper {
    fun splitIntoSegments(
        samples: List<HeartRateRecordData>,
        gapThresholdMs: Long,
    ): List<List<HeartRateRecordData>> {
        if (samples.isEmpty()) return emptyList()
        val segments = mutableListOf<MutableList<HeartRateRecordData>>()
        var current = mutableListOf(samples[0])
        for (i in 1 until samples.size) {
            if (samples[i].timestampMs - samples[i - 1].timestampMs > gapThresholdMs) {
                segments.add(current)
                current = mutableListOf(samples[i])
            } else {
                current.add(samples[i])
            }
        }
        segments.add(current)
        return segments
    }
}

@Composable
fun SleepHrChart(
    session: SleepSessionData?,
    samples: List<HeartRateRecordData>,
    modifier: Modifier = Modifier,
) {
    if (session == null || samples.isEmpty()) {
        CalibrationBar(
            modifier = modifier,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val sortedSamples = remember(samples) { samples.sortedBy { it.timestampMs } }
    val zoneId = remember { ZoneId.systemDefault() }
    val timeFormatter =
        remember(zoneId) { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(zoneId) }

    // Pulsing animation for the selected point, matching SleepStagesChart's halo directly above this chart
    val infiniteTransition = rememberInfiniteTransition(label = "sleepHrPulseTransition")
    val pulseRadiusCoeffState =
        infiniteTransition.animateFloat(
            initialValue = 1.0f,
            targetValue = 1.6f,
            animationSpec =
                infiniteRepeatable(animation = tween(1200, easing = EaseInOutSine), repeatMode = RepeatMode.Reverse),
            label = "sleepHrPulseRadiusCoeff",
        )
    val pulseAlphaState =
        infiniteTransition.animateFloat(
            initialValue = 0.15f,
            targetValue = 0.4f,
            animationSpec =
                infiniteRepeatable(animation = tween(1200, easing = EaseInOutSine), repeatMode = RepeatMode.Reverse),
            label = "sleepHrPulseAlpha",
        )

    val lineColor = MaterialTheme.colorScheme.primary
    val axisTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    val axisLineColor = MaterialTheme.colorScheme.outlineVariant

    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelStyle = TextStyle(color = axisTextColor, fontSize = 10.sp)

    val yMin = remember(sortedSamples) { (sortedSamples.minOf { it.beatsPerMinute } - 10).coerceAtLeast(30) }
    val yMax =
        remember(sortedSamples, yMin) {
            (sortedSamples.maxOf { it.beatsPerMinute } + 10).coerceAtLeast(yMin + 20)
        }

    val scale = remember(session.startTime, session.endTime) { DayTimelineScale(session.startTime, session.endTime) }

    var scaleX by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var selectedSample by remember { mutableStateOf<HeartRateRecordData?>(null) }

    LaunchedEffect(session.id, sortedSamples) {
        if (selectedSample != null && sortedSamples.none { it.timestampMs == selectedSample?.timestampMs }) {
            selectedSample = null
        }
    }

    LaunchedEffect(session.id) {
        scaleX = 1f
        offsetX = 0f
    }

    val segments =
        remember(sortedSamples) { SleepHrChartHelper.splitIntoSegments(sortedSamples, SLEEP_HR_GAP_THRESHOLD_MS) }
    val labelTimestamps =
        remember(session.startTime, session.endTime) { getLabelTimestamps(session.startTime, session.endTime) }
    val yLabels =
        remember(yMin, yMax) {
            (0 until Y_TICK_COUNT).map { i -> yMin + (yMax - yMin) * i / (Y_TICK_COUNT - 1) }
        }

    BoxWithConstraints(modifier = modifier) {
        val chartWidthPx = with(density) { maxWidth.toPx() }
        val leftLabelWidthPx = with(density) { LEFT_LABEL_WIDTH.toPx() }
        val plotW = chartWidthPx - leftLabelWidthPx

        fun timestampToX(timestampMs: Long): Float {
            val frac = scale.fraction(timestampMs)
            return leftLabelWidthPx + frac * plotW
        }

        fun zoomedX(timestampMs: Long): Float =
            leftLabelWidthPx + (timestampToX(timestampMs) - leftLabelWidthPx) * scaleX + offsetX

        val bpmTemplate = stringResource(R.string.sleep_hr_tooltip_value)

        val tooltipState =
            remember(selectedSample, scaleX, offsetX, plotW, scale, yMin, yMax, bpmTemplate) {
                val sample = selectedSample ?: return@remember null
                val bottomLabelHeightPx = with(density) { BOTTOM_LABEL_HEIGHT.toPx() }
                val canvasHeightPx = with(density) { CHART_HEIGHT.toPx() }
                val plotTop = 0f
                val plotBottom = canvasHeightPx - bottomLabelHeightPx
                val plotH = plotBottom - plotTop

                val sampleX = zoomedX(sample.timestampMs)
                val sampleY =
                    plotTop + (1f - (sample.beatsPerMinute - yMin).toFloat() / (yMax - yMin).toFloat()) * plotH

                val timeStr = timeFormatter.format(Instant.ofEpochMilli(sample.timestampMs))

                DataPointTooltipData(
                    valueText = String.format(Locale.getDefault(), bpmTemplate, sample.beatsPerMinute),
                    dateText = timeStr,
                    offset = IntOffset(sampleX.roundToInt(), sampleY.roundToInt()),
                )
            }

        val prevActionLabel = stringResource(CoreUiR.string.action_previous_point)
        val nextActionLabel = stringResource(CoreUiR.string.action_next_point)
        val clearActionLabel = stringResource(CoreUiR.string.action_clear_selection)

        val customActionsList =
            remember(selectedSample, sortedSamples) {
                val list = mutableListOf<CustomAccessibilityAction>()
                if (sortedSamples.isNotEmpty()) {
                    list.add(
                        CustomAccessibilityAction(prevActionLabel) {
                            val currentIndex =
                                sortedSamples.indexOfFirst { it.timestampMs == selectedSample?.timestampMs }
                            selectedSample =
                                if (currentIndex > 0) sortedSamples[currentIndex - 1] else sortedSamples.last()
                            true
                        },
                    )
                    list.add(
                        CustomAccessibilityAction(nextActionLabel) {
                            val currentIndex =
                                sortedSamples.indexOfFirst { it.timestampMs == selectedSample?.timestampMs }
                            selectedSample =
                                if (currentIndex != -1 && currentIndex < sortedSamples.lastIndex) {
                                    sortedSamples[currentIndex + 1]
                                } else {
                                    sortedSamples.first()
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

        val chartSummary = stringResource(R.string.chart_accessibility_sleep_hr_summary)
        val selectedValueDescription =
            selectedSample?.let { sample ->
                val timeStr = timeFormatter.format(Instant.ofEpochMilli(sample.timestampMs))
                stringResource(R.string.chart_accessibility_selected_sleep_hr, sample.beatsPerMinute, timeStr)
            } ?: stringResource(CoreUiR.string.chart_accessibility_no_selection)

        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(CHART_HEIGHT)
                    .testTag("SleepHrChartCanvas")
                    .semantics {
                        contentDescription = chartSummary
                        stateDescription = selectedValueDescription
                        customActions = customActionsList
                    }.pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scaleX = (scaleX * zoom).coerceIn(1f, 5f)
                            val maxOffset = (scaleX - 1f) * plotW
                            offsetX = (offsetX + pan.x).coerceIn(-maxOffset, 0f)
                        }
                    }.pointerInput(sortedSamples, session.id, scaleX, offsetX, scale) {
                        detectTapGestures { tapOffset ->
                            val tappedZoomedX = tapOffset.x
                            val tappedUnscaledX =
                                leftLabelWidthPx + (tappedZoomedX - leftLabelWidthPx - offsetX) / scaleX

                            val bottomLabelHeightPx = BOTTOM_LABEL_HEIGHT.toPx()
                            val plotLeft = leftLabelWidthPx
                            val plotTop = 0f
                            val plotRight = size.width.toFloat()
                            val plotBottom = size.height.toFloat() - bottomLabelHeightPx
                            val plotRect = Rect(plotLeft, plotTop, plotRight, plotBottom)
                            if (!plotRect.contains(tapOffset)) return@detectTapGestures

                            val tapFrac = ((tappedUnscaledX - plotLeft) / plotW).coerceIn(0f, 1f)
                            val tapMs = session.startTime + (tapFrac * scale.durationMs).toLong()

                            val nearest =
                                sortedSamples.minByOrNull { abs(it.timestampMs - tapMs) } ?: return@detectTapGestures
                            selectedSample = nearest
                        }
                    },
        ) {
            val plotLeft = leftLabelWidthPx
            val bottomLabelHeight = with(density) { BOTTOM_LABEL_HEIGHT.toPx() }
            val plotTop = 0f
            val plotRight = size.width
            val plotBottom = size.height - bottomLabelHeight
            val plotH = plotBottom - plotTop

            fun bpmToY(bpm: Int): Float = plotTop + (1f - (bpm - yMin).toFloat() / (yMax - yMin).toFloat()) * plotH

            val gridLineColor = axisLineColor.copy(alpha = 0.4f)
            val strokePx = 1.dp.toPx()

            for (bpm in yLabels) {
                val y = bpmToY(bpm)
                if (y < plotBottom && y > plotTop) {
                    drawLine(gridLineColor, Offset(plotLeft, y), Offset(plotRight, y), strokePx)
                }
            }

            for (ts in labelTimestamps) {
                val x = zoomedX(ts)
                if (x in plotLeft..plotRight) {
                    drawLine(gridLineColor, Offset(x, plotTop), Offset(x, plotBottom), strokePx)
                }
            }

            drawLine(axisLineColor, Offset(plotLeft, plotBottom), Offset(plotRight, plotBottom), 1.dp.toPx())

            for (bpm in yLabels) {
                val y = bpmToY(bpm)
                if (y < plotBottom - 4.dp.toPx() && y > plotTop + 4.dp.toPx()) {
                    val measured = textMeasurer.measure(bpm.toString(), labelStyle)
                    drawText(
                        textLayoutResult = measured,
                        topLeft = Offset(plotLeft - measured.size.width - 4.dp.toPx(), y - measured.size.height / 2f),
                    )
                }
            }

            for (ts in labelTimestamps) {
                val x = zoomedX(ts)
                if (x in plotLeft..plotRight) {
                    val label = timeFormatter.format(Instant.ofEpochMilli(ts))
                    val measured = textMeasurer.measure(label, labelStyle)
                    drawText(
                        textLayoutResult = measured,
                        topLeft =
                            Offset(
                                (x - measured.size.width / 2f).coerceIn(plotLeft, plotRight - measured.size.width),
                                plotBottom + 2.dp.toPx(),
                            ),
                    )
                }
            }

            clipRect(left = plotLeft, top = plotTop, right = plotRight, bottom = plotBottom) {
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

            if (selectedSample != null) {
                val selectedX = zoomedX(selectedSample!!.timestampMs)
                val selectedY = bpmToY(selectedSample!!.beatsPerMinute)
                if (selectedX in plotLeft..plotRight) {
                    drawLine(
                        color = lineColor.copy(alpha = 0.4f),
                        start = Offset(selectedX, plotTop),
                        end = Offset(selectedX, plotBottom),
                        strokeWidth = 1.5.dp.toPx(),
                    )
                    drawCircle(
                        color = lineColor.copy(alpha = pulseAlphaState.value),
                        radius = 8.dp.toPx() * pulseRadiusCoeffState.value,
                        center = Offset(selectedX, selectedY),
                    )
                    drawCircle(color = lineColor, radius = 4.dp.toPx(), center = Offset(selectedX, selectedY))
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
