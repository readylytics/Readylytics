package app.readylytics.health.feature.sleep

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.model.domain.repository.HeartRateRecordData
import app.readylytics.health.core.model.domain.repository.HeartRateResolution
import app.readylytics.health.core.model.domain.repository.SleepSessionData
import app.readylytics.health.core.ui.components.DataPointTooltip
import app.readylytics.health.core.ui.components.DataPointTooltipData
import app.readylytics.health.core.ui.components.DayTimelineScale
import app.readylytics.health.core.ui.components.detectLongPressSelection
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import app.readylytics.health.core.ui.R as CoreUiR

// R2-UI-002 follow-up: SleepHrChart was already over the LongMethod/CyclomaticComplexMethod
// detekt thresholds before Task 9 added the `resolution` parameter (which merely shifted the
// baseline's signature-keyed IDs). Rather than re-key the pre-existing debt, this chart's state,
// pure math, and Canvas rendering were split into SleepHrChartState.kt / SleepHrChartMath.kt /
// SleepHrChartCanvasRenderer.kt so every function here clears detekt with no baseline entries.
// No draw call, color, size, or conditional branch changes meaning below -- only how the logic
// is organized into functions and files.

internal const val SLEEP_HR_GAP_THRESHOLD_MS = 10 * 60 * 1000L // 10 minutes
internal const val SLEEP_HR_TREND_WINDOW_MS = 15 * 60 * 1000L // 15-minute centered rolling average
internal const val SLEEP_HR_Y_TICK_COUNT = 4
internal val SLEEP_HR_LEFT_LABEL_WIDTH = 44.dp
internal val SLEEP_HR_BOTTOM_LABEL_HEIGHT = 20.dp
internal val SLEEP_HR_TOP_LABEL_PADDING = 10.dp
internal val SLEEP_HR_CHART_HEIGHT = 220.dp
internal val SLEEP_HR_X_LABEL_SPACING = 8.dp

/** A point on the smoothed HR trend line: a rolling average, not a raw sensor sample. */
internal data class SleepHrTrendPoint(
    val timestampMs: Long,
    val avgBpm: Float,
)

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

    /**
     * Centered rolling average of bpm within [windowMs] of each sample's timestamp, computed per
     * segment (never averages across a gap) via an O(n) sliding window over the already-sorted
     * segment. Smooths the raw per-sample jaggedness so the overnight HR course -- and where its
     * minimum falls in time -- reads clearly without a separate nadir marker.
     */
    fun computeTrendLine(
        segment: List<HeartRateRecordData>,
        windowMs: Long,
    ): List<SleepHrTrendPoint> {
        if (segment.isEmpty()) return emptyList()
        val halfWindow = windowMs / 2
        var lo = 0
        var hi = 0
        var sum = 0L
        val result = ArrayList<SleepHrTrendPoint>(segment.size)
        for (i in segment.indices) {
            val t = segment[i].timestampMs
            while (hi < segment.size && segment[hi].timestampMs <= t + halfWindow) {
                sum += segment[hi].beatsPerMinute
                hi++
            }
            while (segment[lo].timestampMs < t - halfWindow) {
                sum -= segment[lo].beatsPerMinute
                lo++
            }
            result.add(SleepHrTrendPoint(t, sum.toFloat() / (hi - lo)))
        }
        return result
    }
}

@Composable
fun SleepHrChart(
    session: SleepSessionData?,
    samples: List<HeartRateRecordData>,
    modifier: Modifier = Modifier,
    resolution: HeartRateResolution = HeartRateResolution.RAW,
) {
    if (session == null || samples.isEmpty()) {
        CalibrationBar(
            modifier = modifier,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    SleepHrChartContent(session = session, samples = samples, modifier = modifier, resolution = resolution)
}

@Composable
private fun SleepHrChartContent(
    session: SleepSessionData,
    samples: List<HeartRateRecordData>,
    modifier: Modifier,
    resolution: HeartRateResolution,
) {
    val zoneId = remember { ZoneId.systemDefault() }
    val timeFormatter =
        remember(zoneId) { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(zoneId) }
    val data = rememberSleepHrDerivedData(session, samples)
    val scale = remember(session.startTime, session.endTime) { DayTimelineScale(session.startTime, session.endTime) }
    val interaction = rememberSleepHrInteractionState(session, data.sortedSamples)
    val pulse = rememberSleepHrPulseAnimation()
    val style = rememberSleepHrChartStyle(timeFormatter)

    val state = SleepHrChartState(session, data, scale, interaction, pulse, style, resolution)
    SleepHrChartCanvasArea(state = state, modifier = modifier)
}

@Composable
private fun SleepHrChartCanvasArea(
    state: SleepHrChartState,
    modifier: Modifier,
) {
    var scaleX by state.interaction.scaleX
    var offsetX by state.interaction.offsetX

    Column(modifier = modifier) {
        SleepHrResolutionLabel(state.resolution)
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val density = LocalDensity.current
            val leftLabelWidthPx = with(density) { SLEEP_HR_LEFT_LABEL_WIDTH.toPx() }
            val plotW = with(density) { maxWidth.toPx() } - leftLabelWidthPx

            fun zoomedX(timestampMs: Long): Float =
                sleepHrZoomedX(timestampMs, state.scale, leftLabelWidthPx, plotW, scaleX, offsetX)

            val bpmTemplate = stringResource(R.string.sleep_hr_tooltip_value)
            val bottomLabelHeightPx = with(density) { SLEEP_HR_BOTTOM_LABEL_HEIGHT.toPx() }
            val topLabelPaddingPx = with(density) { SLEEP_HR_TOP_LABEL_PADDING.toPx() }
            val canvasHeightPx = with(density) { SLEEP_HR_CHART_HEIGHT.toPx() }

            val tooltipState =
                remember(
                    state.interaction.selectedSample.value,
                    scaleX,
                    offsetX,
                    plotW,
                    state.scale,
                    state.data.yMin,
                    state.data.yMax,
                    bpmTemplate,
                    topLabelPaddingPx,
                ) {
                    computeSleepHrTooltip(
                        selectedSample = state.interaction.selectedSample.value,
                        yRange = state.data.yMin..state.data.yMax,
                        zoomedX = ::zoomedX,
                        plotTop = topLabelPaddingPx,
                        plotBottom = canvasHeightPx - bottomLabelHeightPx,
                        timeFormatter = state.style.timeFormatter,
                        bpmTemplate = bpmTemplate,
                    )
                }

            val accessibility =
                rememberSleepHrAccessibility(
                    state.data.sortedSamples,
                    state.interaction.selectedSample,
                    state.style.timeFormatter,
                )

            SleepHrChartVisuals(
                state = state,
                leftLabelWidthPx = leftLabelWidthPx,
                plotW = plotW,
                tooltipState = tooltipState,
                accessibility = accessibility,
            )
        }
        Spacer(Modifier.height(MaterialTheme.spacing.extraSmallMedium))
        SleepHrChartLegend(
            lineColor = state.style.lineColor,
            trendColor = state.style.trendColor,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun SleepHrChartLegend(
    lineColor: Color,
    trendColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendSwatch(color = lineColor, label = stringResource(R.string.sleep_hr_legend_actual))
        LegendSwatch(color = trendColor, label = stringResource(R.string.sleep_hr_legend_trend))
    }
}

@Composable
internal fun LegendSwatch(
    color: Color,
    label: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier =
                Modifier
                    .size(width = 16.dp, height = 2.dp)
                    .background(color),
        )
        Spacer(Modifier.width(MaterialTheme.spacing.extraSmallMedium))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SleepHrResolutionLabel(resolution: HeartRateResolution) {
    if (resolution == HeartRateResolution.RECONSTRUCTED) {
        Text(
            text = stringResource(CoreUiR.string.heart_rate_resolution_reconstructed),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SleepHrChartVisuals(
    state: SleepHrChartState,
    leftLabelWidthPx: Float,
    plotW: Float,
    tooltipState: DataPointTooltipData?,
    accessibility: SleepHrAccessibility,
) {
    var scaleX by state.interaction.scaleX
    var offsetX by state.interaction.offsetX
    var selectedSample by state.interaction.selectedSample
    val hapticFeedback = LocalHapticFeedback.current

    Canvas(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(SLEEP_HR_CHART_HEIGHT)
                .testTag("SleepHrChartCanvas")
                .semantics {
                    contentDescription = accessibility.chartSummary
                    stateDescription = accessibility.selectedValueDescription
                    customActions = accessibility.customActions
                }.pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val (newScaleX, newOffsetX) = sleepHrZoomPan(scaleX, offsetX, pan, zoom, plotW)
                        scaleX = newScaleX
                        offsetX = newOffsetX
                    }
                }.pointerInput(state.data.sortedSamples, state.session.id, scaleX, offsetX, state.scale) {
                    // Long press selects, then scrubs; a short tap only dismisses, so a scroll
                    // that starts on the chart never opens a tooltip.
                    detectLongPressSelection(
                        hapticFeedback = hapticFeedback,
                        isVisible = { selectedSample != null },
                        onDismiss = { selectedSample = null },
                    ) { touch ->
                        val tappedUnscaledX = leftLabelWidthPx + (touch.x - leftLabelWidthPx - offsetX) / scaleX
                        resolveTappedSleepHrSample(
                            tapOffset = touch,
                            tappedUnscaledX = tappedUnscaledX,
                            leftLabelWidthPx = leftLabelWidthPx,
                            plotW = plotW,
                            sessionStartMs = state.session.startTime,
                            scale = state.scale,
                            sortedSamples = state.data.sortedSamples,
                        )?.also { selectedSample = it } != null
                    }
                },
    ) {
        renderSleepHrCanvas(
            data = state.data,
            style = state.style,
            selectedSample = selectedSample,
            pulse = state.pulse,
            leftLabelWidthPx = leftLabelWidthPx,
            zoomedX = { ts -> sleepHrZoomedX(ts, state.scale, leftLabelWidthPx, plotW, scaleX, offsetX) },
        )
    }

    if (tooltipState != null) {
        DataPointTooltip(
            isVisible = true,
            data = tooltipState,
            onDismissRequest = { selectedSample = null },
        )
    }
}
