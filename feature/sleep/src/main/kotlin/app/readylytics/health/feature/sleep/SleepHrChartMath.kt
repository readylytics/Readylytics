package app.readylytics.health.feature.sleep

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.unit.IntOffset
import app.readylytics.health.core.model.domain.repository.HeartRateRecordData
import app.readylytics.health.core.ui.common.nearestByTime
import app.readylytics.health.core.ui.components.DataPointTooltipData
import app.readylytics.health.core.ui.components.DayTimelineScale
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

// See the file-header comment in SleepHrChart.kt: pure (non-Composable) plot math extracted out
// of SleepHrChart so it clears detekt's LongMethod/CyclomaticComplexMethod/TooManyFunctions/
// LongParameterList thresholds without changing any behavior.

internal fun sleepHrZoomedX(
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

internal fun sleepHrZoomPan(
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

internal fun findTouchedSleepHrSample(
    touchOffset: Offset,
    touchedUnscaledX: Float,
    plotRect: Rect,
    plotW: Float,
    sessionStartMs: Long,
    scale: DayTimelineScale,
    sortedSamples: List<HeartRateRecordData>,
): HeartRateRecordData? {
    if (!plotRect.contains(touchOffset)) return null
    val touchFrac = ((touchedUnscaledX - plotRect.left) / plotW).coerceIn(0f, 1f)
    val touchMs = sessionStartMs + (touchFrac * scale.durationMs).toLong()
    return sortedSamples.nearestByTime(touchMs) { it.timestampMs }
}

/** Resolves the touched gesture coordinate to the nearest sample, using the PointerInputScope's own size/density. */
internal fun PointerInputScope.resolveTouchedSleepHrSample(
    touchOffset: Offset,
    touchedUnscaledX: Float,
    leftLabelWidthPx: Float,
    plotW: Float,
    sessionStartMs: Long,
    scale: DayTimelineScale,
    sortedSamples: List<HeartRateRecordData>,
): HeartRateRecordData? {
    val bottomLabelHeightPx = SLEEP_HR_BOTTOM_LABEL_HEIGHT.toPx()
    val topLabelPaddingPx = SLEEP_HR_TOP_LABEL_PADDING.toPx()
    val plotRect =
        Rect(leftLabelWidthPx, topLabelPaddingPx, size.width.toFloat(), size.height.toFloat() - bottomLabelHeightPx)
    return findTouchedSleepHrSample(
        touchOffset,
        touchedUnscaledX,
        plotRect,
        plotW,
        sessionStartMs,
        scale,
        sortedSamples,
    )
}

internal fun computeSleepHrTooltip(
    selectedSample: HeartRateRecordData?,
    yRange: IntRange,
    zoomedX: (Long) -> Float,
    plotTop: Float,
    plotBottom: Float,
    timeFormatter: DateTimeFormatter,
    bpmTemplate: String,
): DataPointTooltipData? {
    val sample = selectedSample ?: return null
    val plotH = plotBottom - plotTop

    val sampleX = zoomedX(sample.timestampMs)
    val sampleY =
        plotTop +
            (1f - (sample.beatsPerMinute - yRange.first).toFloat() / (yRange.last - yRange.first).toFloat()) * plotH
    val timeStr = timeFormatter.format(Instant.ofEpochMilli(sample.timestampMs))

    return DataPointTooltipData(
        valueText = String.format(Locale.getDefault(), bpmTemplate, sample.beatsPerMinute),
        dateText = timeStr,
        offset = IntOffset(sampleX.roundToInt(), sampleY.roundToInt()),
    )
}
