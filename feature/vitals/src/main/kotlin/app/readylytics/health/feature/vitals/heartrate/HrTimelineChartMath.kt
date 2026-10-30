package app.readylytics.health.feature.vitals.heartrate

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.unit.IntOffset
import app.readylytics.health.core.ui.common.nearestByTime
import app.readylytics.health.core.ui.components.DataPointTooltipData
import app.readylytics.health.core.ui.components.DayTimelineScale
import app.readylytics.health.core.ui.model.HrSample
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

// See the file-header comment in HrTimelineChart.kt: pure (non-Composable) plot math extracted
// out of HrTimelineChartContent so it clears detekt's LongMethod/CyclomaticComplexMethod/
// TooManyFunctions/LongParameterList thresholds without changing any behavior.

internal fun hrTimelineZoomedX(
    timestampMs: Long,
    scale: DayTimelineScale,
    leftLabelWidthPx: Float,
    plotW: Float,
    scaleX: Float,
    offsetX: Float,
): Float {
    val unscaledX = leftLabelWidthPx + scale.fraction(timestampMs) * plotW
    return leftLabelWidthPx + (unscaledX - leftLabelWidthPx) * scaleX + offsetX
}

internal fun hrTimelineZoomPan(
    scaleX: Float,
    offsetX: Float,
    pan: Offset,
    zoom: Float,
    plotW: Float,
): Pair<Float, Float> {
    val newScaleX = (scaleX * zoom).coerceIn(1f, 5f)
    val maxOffset = (newScaleX - 1f) * plotW
    val newOffsetX = (offsetX + pan.x).coerceIn(-maxOffset, 0f)
    return newScaleX to newOffsetX
}

/** Nearest sample to the touch inside [plotRect]; [samples] must be sorted ascending by time. */
internal fun findTouchedHrTimelineSample(
    touchOffset: Offset,
    touchedUnscaledX: Float,
    plotRect: Rect,
    plotW: Float,
    dayStartMs: Long,
    scale: DayTimelineScale,
    samples: List<HrSample>,
): HrSample? {
    if (!plotRect.contains(touchOffset)) return null
    val touchFrac = ((touchedUnscaledX - plotRect.left) / plotW).coerceIn(0f, 1f)
    val touchMs = dayStartMs + (touchFrac * scale.durationMs).toLong()
    return samples.nearestByTime(touchMs) { it.timeMs }
}

/** Resolves the touched gesture coordinate to the nearest sample, using the PointerInputScope's own size/density. */
internal fun PointerInputScope.resolveTouchedHrTimelineSample(
    touchOffset: Offset,
    touchedUnscaledX: Float,
    leftLabelWidthPx: Float,
    plotW: Float,
    dayStartMs: Long,
    scale: DayTimelineScale,
    samples: List<HrSample>,
): HrSample? {
    val bottomLabelHeightPx = HR_TIMELINE_BOTTOM_LABEL_HEIGHT.toPx()
    val plotRect = Rect(leftLabelWidthPx, 0f, size.width.toFloat(), size.height.toFloat() - bottomLabelHeightPx)
    return findTouchedHrTimelineSample(touchOffset, touchedUnscaledX, plotRect, plotW, dayStartMs, scale, samples)
}

internal fun computeHrTimelineTooltip(
    selectedSample: HrSample?,
    yMin: Int,
    yMax: Int,
    zoomedX: (Long) -> Float,
    plotBottom: Float,
    zoneId: ZoneId,
): DataPointTooltipData? {
    val sample = selectedSample ?: return null
    val plotTop = 0f
    val plotH = plotBottom - plotTop

    val sampleX = zoomedX(sample.timeMs)
    val sampleY = plotTop + (1f - (sample.bpm - yMin).toFloat() / (yMax - yMin).toFloat()) * plotH
    val timeStr = Instant.ofEpochMilli(sample.timeMs).atZone(zoneId).format(HR_TIMELINE_HOUR_FORMATTER)

    return DataPointTooltipData(
        valueText = "${sample.bpm} bpm",
        dateText = timeStr,
        offset = IntOffset(sampleX.roundToInt(), sampleY.roundToInt()),
    )
}
