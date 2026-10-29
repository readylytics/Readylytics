package app.readylytics.health.feature.sleep

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.model.domain.repository.HrvRecordData
import app.readylytics.health.core.model.domain.repository.SleepSessionData
import app.readylytics.health.core.ui.components.BaselineLegend
import app.readylytics.health.core.ui.components.DataPointTooltip
import app.readylytics.health.core.ui.components.DataPointTooltipData
import app.readylytics.health.core.ui.components.DayTimelineScale
import app.readylytics.health.core.ui.components.detectLongPressSelection
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import app.readylytics.health.core.ui.R as CoreUiR

// Structured like SleepHrChart.kt: state/pure-math/Canvas-rendering split across
// SleepHrvChartState.kt / SleepHrvChartMath.kt / SleepHrvChartCanvasRenderer.kt.

// HRV (RMSSD) is sampled far more sparsely than continuous HR -- healthy overnight gaps between
// readings routinely exceed HR's 10-minute threshold. 2 hours keeps a normal night's readings on
// one connected line while still breaking on a genuine multi-hour sensor dropout.
internal const val SLEEP_HRV_GAP_THRESHOLD_MS = 2 * 60 * 60 * 1000L
internal const val SLEEP_HRV_Y_TICK_COUNT = 4
internal val SLEEP_HRV_LEFT_LABEL_WIDTH = 44.dp
internal val SLEEP_HRV_BOTTOM_LABEL_HEIGHT = 20.dp
internal val SLEEP_HRV_TOP_LABEL_PADDING = 10.dp
internal val SLEEP_HRV_CHART_HEIGHT = 220.dp
internal val SLEEP_HRV_X_LABEL_SPACING = 8.dp

internal object SleepHrvChartHelper {
    fun splitIntoSegments(
        samples: List<HrvRecordData>,
        gapThresholdMs: Long,
    ): List<List<HrvRecordData>> {
        if (samples.isEmpty()) return emptyList()
        val segments = mutableListOf<MutableList<HrvRecordData>>()
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
fun SleepHrvChart(
    session: SleepSessionData?,
    samples: List<HrvRecordData>,
    avgHrv: Float?,
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
    SleepHrvChartContent(session = session, samples = samples, avgHrv = avgHrv, modifier = modifier)
}

@Composable
private fun SleepHrvChartContent(
    session: SleepSessionData,
    samples: List<HrvRecordData>,
    avgHrv: Float?,
    modifier: Modifier,
) {
    val zoneId = remember { ZoneId.systemDefault() }
    val timeFormatter =
        remember(zoneId) { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(zoneId) }
    val data = rememberSleepHrvDerivedData(session, samples, avgHrv)
    val scale = remember(session.startTime, session.endTime) { DayTimelineScale(session.startTime, session.endTime) }
    val interaction = rememberSleepHrvInteractionState(session, data.sortedSamples)
    val pulse = rememberSleepHrPulseAnimation()
    val style = rememberSleepHrvChartStyle(timeFormatter)

    val state = SleepHrvChartState(session, data, scale, interaction, pulse, style, avgHrv)
    SleepHrvChartCanvasArea(state = state, modifier = modifier)
}

private data class SleepHrvCanvasComputed(
    val tooltipState: DataPointTooltipData?,
    val accessibility: SleepHrvAccessibility,
)

@Composable
private fun rememberSleepHrvCanvasComputed(
    state: SleepHrvChartState,
    scaleX: Float,
    offsetX: Float,
    leftLabelWidthPx: Float,
    plotW: Float,
    density: Density,
): SleepHrvCanvasComputed {
    fun zoomedX(timestampMs: Long): Float =
        sleepHrZoomedX(timestampMs, state.scale, leftLabelWidthPx, plotW, scaleX, offsetX)

    val msTemplate = stringResource(R.string.sleep_hrv_tooltip_value)
    val bottomLabelHeightPx = with(density) { SLEEP_HRV_BOTTOM_LABEL_HEIGHT.toPx() }
    val topLabelPaddingPx = with(density) { SLEEP_HRV_TOP_LABEL_PADDING.toPx() }
    val canvasHeightPx = with(density) { SLEEP_HRV_CHART_HEIGHT.toPx() }

    val tooltipState =
        remember(
            state.interaction.selectedSample.value,
            scaleX,
            offsetX,
            plotW,
            state.scale,
            state.data.yMin,
            state.data.yMax,
            msTemplate,
            topLabelPaddingPx,
        ) {
            computeSleepHrvTooltip(
                selectedSample = state.interaction.selectedSample.value,
                yRange = state.data.yMin..state.data.yMax,
                zoomedX = ::zoomedX,
                plotTop = topLabelPaddingPx,
                plotBottom = canvasHeightPx - bottomLabelHeightPx,
                timeFormatter = state.style.timeFormatter,
                msTemplate = msTemplate,
            )
        }

    val accessibility =
        rememberSleepHrvAccessibility(
            state.data.sortedSamples,
            state.interaction.selectedSample,
            state.style.timeFormatter,
        )

    return SleepHrvCanvasComputed(tooltipState, accessibility)
}

@Composable
private fun SleepHrvChartCanvasArea(
    state: SleepHrvChartState,
    modifier: Modifier,
) {
    var scaleX by state.interaction.scaleX
    var offsetX by state.interaction.offsetX

    Column(modifier = modifier) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val density = LocalDensity.current
            val leftLabelWidthPx = with(density) { SLEEP_HRV_LEFT_LABEL_WIDTH.toPx() }
            val plotW = with(density) { maxWidth.toPx() } - leftLabelWidthPx

            val computed = rememberSleepHrvCanvasComputed(state, scaleX, offsetX, leftLabelWidthPx, plotW, density)

            SleepHrvChartVisuals(
                state = state,
                leftLabelWidthPx = leftLabelWidthPx,
                plotW = plotW,
                tooltipState = computed.tooltipState,
                accessibility = computed.accessibility,
            )
        }
        if (state.avgHrv != null) {
            Spacer(Modifier.height(MaterialTheme.spacing.extraSmallMedium))
            BaselineLegend(
                value = state.avgHrv,
                unit = stringResource(CoreUiR.string.unit_ms),
                color = state.style.avgColor,
                label = stringResource(R.string.sleep_hrv_avg_legend_label),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SleepHrvChartVisuals(
    state: SleepHrvChartState,
    leftLabelWidthPx: Float,
    plotW: Float,
    tooltipState: DataPointTooltipData?,
    accessibility: SleepHrvAccessibility,
) {
    var scaleX by state.interaction.scaleX
    var offsetX by state.interaction.offsetX
    var selectedSample by state.interaction.selectedSample
    val hapticFeedback = LocalHapticFeedback.current

    Canvas(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(SLEEP_HRV_CHART_HEIGHT)
                .testTag("SleepHrvChartCanvas")
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
                        resolveTappedSleepHrvSample(
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
        renderSleepHrvCanvas(
            data = state.data,
            style = state.style,
            selectedSample = selectedSample,
            pulse = state.pulse,
            leftLabelWidthPx = leftLabelWidthPx,
            avgHrv = state.avgHrv,
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
