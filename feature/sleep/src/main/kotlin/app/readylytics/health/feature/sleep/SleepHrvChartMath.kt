package app.readylytics.health.feature.sleep

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.unit.IntOffset
import app.readylytics.health.core.model.domain.repository.HrvRecordData
import app.readylytics.health.core.ui.common.nearestByTime
import app.readylytics.health.core.ui.components.DataPointTooltipData
import app.readylytics.health.core.ui.components.DayTimelineScale
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

// Pure (non-Composable) plot math for the nightly HRV chart, mirroring SleepHrChartMath.kt's
// touch-resolution/tooltip helpers for HrvRecordData. sleepHrZoomedX/sleepHrZoomPan are geometry-only
// (no HR-specific types) so this chart reuses them directly instead of duplicating.

internal fun findTouchedSleepHrvSample(
    touchOffset: Offset,
    touchedUnscaledX: Float,
    plotRect: Rect,
    plotW: Float,
    sessionStartMs: Long,
    scale: DayTimelineScale,
    sortedSamples: List<HrvRecordData>,
): HrvRecordData? {
    if (!plotRect.contains(touchOffset)) return null
    val touchFrac = ((touchedUnscaledX - plotRect.left) / plotW).coerceIn(0f, 1f)
    val touchMs = sessionStartMs + (touchFrac * scale.durationMs).toLong()
    return sortedSamples.nearestByTime(touchMs) { it.timestampMs }
}

/** Resolves the touched gesture coordinate to the nearest sample, using the PointerInputScope's own size/density. */
internal fun PointerInputScope.resolveTouchedSleepHrvSample(
    touchOffset: Offset,
    touchedUnscaledX: Float,
    leftLabelWidthPx: Float,
    plotW: Float,
    sessionStartMs: Long,
    scale: DayTimelineScale,
    sortedSamples: List<HrvRecordData>,
): HrvRecordData? {
    val bottomLabelHeightPx = SLEEP_HRV_BOTTOM_LABEL_HEIGHT.toPx()
    val topLabelPaddingPx = SLEEP_HRV_TOP_LABEL_PADDING.toPx()
    val plotRect =
        Rect(leftLabelWidthPx, topLabelPaddingPx, size.width.toFloat(), size.height.toFloat() - bottomLabelHeightPx)
    return findTouchedSleepHrvSample(
        touchOffset,
        touchedUnscaledX,
        plotRect,
        plotW,
        sessionStartMs,
        scale,
        sortedSamples,
    )
}

internal fun computeSleepHrvTooltip(
    selectedSample: HrvRecordData?,
    yRange: IntRange,
    zoomedX: (Long) -> Float,
    plotTop: Float,
    plotBottom: Float,
    timeFormatter: DateTimeFormatter,
    msTemplate: String,
): DataPointTooltipData? {
    val sample = selectedSample ?: return null
    val plotH = plotBottom - plotTop

    val sampleX = zoomedX(sample.timestampMs)
    val sampleY = plotTop + (1f - (sample.rmssdMs - yRange.first) / (yRange.last - yRange.first).toFloat()) * plotH
    val timeStr = timeFormatter.format(Instant.ofEpochMilli(sample.timestampMs))

    return DataPointTooltipData(
        valueText = String.format(Locale.getDefault(), msTemplate, sample.rmssdMs.roundToInt()),
        dateText = timeStr,
        offset = IntOffset(sampleX.roundToInt(), sampleY.roundToInt()),
    )
}
