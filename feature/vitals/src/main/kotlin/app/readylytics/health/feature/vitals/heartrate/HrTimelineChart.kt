package app.readylytics.health.feature.vitals.heartrate

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.model.domain.repository.HeartRateResolution
import app.readylytics.health.core.ui.components.DataPointTooltip
import app.readylytics.health.core.ui.components.DataPointTooltipData
import app.readylytics.health.core.ui.components.DayTimelineScale
import app.readylytics.health.core.ui.components.EmptyChartPlaceholder
import app.readylytics.health.core.ui.model.HrSample
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import app.readylytics.health.core.ui.R as CoreUiR

// R2-UI-002 follow-up: HrTimelineChartContent was already over the LongMethod/
// CyclomaticComplexMethod/LongParameterList detekt thresholds before Task 9 added the
// `resolution` parameter (which merely shifted the baseline's signature-keyed IDs). Rather than
// re-key the pre-existing debt, this chart's state, pure math, and Canvas rendering were split
// into HrTimelineChartState.kt / HrTimelineChartMath.kt / HrTimelineChartCanvasRenderer.kt, and
// the five zoneNMinBpm/zoneNMaxBpm Ints were bundled into HrZoneBounds (below) so every function
// here clears detekt with no baseline entries. No draw call, color, size, or conditional branch
// changes meaning below -- only how the logic is organized into functions and files. HrZoneBounds
// is public (not internal) because :app's ChartAccessibilityTest also calls HrTimelineChart.

data class HrZoneBounds(
    val zone1MinBpm: Int,
    val zone1MaxBpm: Int,
    val zone2MaxBpm: Int,
    val zone3MaxBpm: Int,
    val zone4MaxBpm: Int,
)

internal const val HR_TIMELINE_GAP_THRESHOLD_MS = 10 * 60 * 1000L // 10 minutes
internal val HR_TIMELINE_HOUR_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
internal val HR_TIMELINE_LEFT_LABEL_WIDTH = 36.dp
internal val HR_TIMELINE_BOTTOM_LABEL_HEIGHT = 20.dp
internal val HR_TIMELINE_CHART_HEIGHT = 220.dp

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
    zoneBounds: HrZoneBounds,
    modifier: Modifier = Modifier,
    zoneId: ZoneId = ZoneId.systemDefault(),
    resolution: HeartRateResolution = HeartRateResolution.RAW,
) {
    if (samples.isEmpty()) {
        EmptyChartPlaceholder(modifier = modifier)
    } else {
        HrTimelineChartContent(
            samples = samples,
            dayStartMs = dayStartMs,
            dayEndMs = dayEndMs,
            zoneBounds = zoneBounds,
            modifier = modifier,
            zoneId = zoneId,
            resolution = resolution,
        )
    }
}

@Composable
private fun HrTimelineChartContent(
    samples: List<HrSample>,
    dayStartMs: Long,
    dayEndMs: Long,
    zoneBounds: HrZoneBounds,
    modifier: Modifier,
    zoneId: ZoneId,
    resolution: HeartRateResolution,
) {
    val data = rememberHrTimelineDerivedData(samples, dayStartMs, dayEndMs, zoneBounds, zoneId)
    val scale = remember(dayStartMs, dayEndMs) { DayTimelineScale(dayStartMs, dayEndMs) }
    val interaction = rememberHrTimelineInteractionState(dayStartMs, samples)
    val pulse = rememberHrTimelinePulseAnimation()
    val style = rememberHrTimelineChartStyle()

    val state = HrTimelineChartState(dayStartMs, zoneId, zoneBounds, data, scale, interaction, pulse, style, resolution)
    HrTimelineChartCanvasArea(state = state, modifier = modifier)
}

@Composable
private fun HrTimelineChartCanvasArea(
    state: HrTimelineChartState,
    modifier: Modifier,
) {
    var scaleX by state.interaction.scaleX
    var offsetX by state.interaction.offsetX

    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val leftLabelWidthPx = with(density) { HR_TIMELINE_LEFT_LABEL_WIDTH.toPx() }
        val plotW = with(density) { maxWidth.toPx() } - leftLabelWidthPx

        fun zoomedX(timestampMs: Long): Float =
            hrTimelineZoomedX(timestampMs, state.scale, leftLabelWidthPx, plotW, scaleX, offsetX)

        val bottomLabelHeightPx = with(density) { HR_TIMELINE_BOTTOM_LABEL_HEIGHT.toPx() }
        val canvasHeightPx = with(density) { HR_TIMELINE_CHART_HEIGHT.toPx() }

        val tooltipState =
            remember(
                state.interaction.selectedSample.value,
                scaleX,
                offsetX,
                plotW,
                state.scale,
                state.data.yMin,
                state.data.yMax,
                state.zoneId,
            ) {
                computeHrTimelineTooltip(
                    selectedSample = state.interaction.selectedSample.value,
                    yMin = state.data.yMin,
                    yMax = state.data.yMax,
                    zoomedX = ::zoomedX,
                    plotBottom = canvasHeightPx - bottomLabelHeightPx,
                    zoneId = state.zoneId,
                )
            }

        val accessibility =
            rememberHrTimelineAccessibility(state.data.samples, state.interaction.selectedSample, state.zoneId)

        HrTimelineChartVisuals(
            state = state,
            leftLabelWidthPx = leftLabelWidthPx,
            plotW = plotW,
            tooltipState = tooltipState,
            accessibility = accessibility,
        )
    }
}

@Composable
private fun HrResolutionLabel(resolution: HeartRateResolution) {
    if (resolution == HeartRateResolution.RECONSTRUCTED) {
        Text(
            text = stringResource(CoreUiR.string.heart_rate_resolution_reconstructed),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HrTimelineChartVisuals(
    state: HrTimelineChartState,
    leftLabelWidthPx: Float,
    plotW: Float,
    tooltipState: DataPointTooltipData?,
    accessibility: HrTimelineAccessibility,
) {
    var scaleX by state.interaction.scaleX
    var offsetX by state.interaction.offsetX
    var selectedSample by state.interaction.selectedSample

    HrResolutionLabel(state.resolution)

    Canvas(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(HR_TIMELINE_CHART_HEIGHT)
                .testTag("HrTimelineChartCanvas")
                .semantics {
                    contentDescription = accessibility.chartSummary
                    stateDescription = accessibility.selectedValueDescription
                    customActions = accessibility.customActions
                }.pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val (newScaleX, newOffsetX) = hrTimelineZoomPan(scaleX, offsetX, pan, zoom, plotW)
                        scaleX = newScaleX
                        offsetX = newOffsetX
                    }
                }.pointerInput(state.data.samples, state.dayStartMs, scaleX, offsetX, state.scale) {
                    detectTapGestures { tapOffset ->
                        val tappedUnscaledX = leftLabelWidthPx + (tapOffset.x - leftLabelWidthPx - offsetX) / scaleX
                        selectedSample =
                            resolveTappedHrTimelineSample(
                                tapOffset = tapOffset,
                                tappedUnscaledX = tappedUnscaledX,
                                leftLabelWidthPx = leftLabelWidthPx,
                                plotW = plotW,
                                dayStartMs = state.dayStartMs,
                                scale = state.scale,
                                samples = state.data.samples,
                            ) ?: return@detectTapGestures
                    }
                },
    ) {
        renderHrTimelineCanvas(
            data = state.data,
            style = state.style,
            zoneBounds = state.zoneBounds,
            selectedSample = selectedSample,
            pulse = state.pulse,
            leftLabelWidthPx = leftLabelWidthPx,
            zoomedX = { ts -> hrTimelineZoomedX(ts, state.scale, leftLabelWidthPx, plotW, scaleX, offsetX) },
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
