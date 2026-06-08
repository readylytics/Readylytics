package com.gregor.lauritz.healthdashboard.ui.components

import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.R
import com.gregor.lauritz.healthdashboard.domain.model.SleepStageType
import com.gregor.lauritz.healthdashboard.domain.repository.SleepSessionData
import com.gregor.lauritz.healthdashboard.domain.repository.SleepStageData
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

private val LANE_HEIGHT = 56.dp
private val SHAPE_INSET = 4.dp
private val SHAPE_CORNER = 6.dp
private val LABEL_WIDTH = 64.dp
private val CHART_TOTAL_HEIGHT = LANE_HEIGHT * 4

private val NINE_HOURS_MS = 9L * 3_600_000L

private fun getStageColor(
    stageType: String,
    colorScheme: androidx.compose.material3.ColorScheme,
) = when (stageType) {
    SleepStageType.AWAKE.value -> colorScheme.error.copy(alpha = 0.80f)
    SleepStageType.REM.value -> colorScheme.secondary.copy(alpha = 0.80f)
    SleepStageType.LIGHT.value -> colorScheme.primary.copy(alpha = 0.60f)
    SleepStageType.DEEP.value -> colorScheme.primary
    else -> colorScheme.primary.copy(alpha = 0.60f)
}

private const val AWAKE_LANE_INDEX = 0
private const val REM_LANE_INDEX = 1
private const val LIGHT_LANE_INDEX = 2
private const val DEEP_LANE_INDEX = 3

private fun getStageLaneIndex(stageType: String): Int =
    when (stageType) {
        SleepStageType.AWAKE.value -> AWAKE_LANE_INDEX
        SleepStageType.REM.value -> REM_LANE_INDEX
        SleepStageType.LIGHT.value -> LIGHT_LANE_INDEX
        SleepStageType.DEEP.value -> DEEP_LANE_INDEX
        else -> LIGHT_LANE_INDEX
    }

private fun mergeConsecutiveStages(stages: List<SleepStageData>): List<SleepStageData> {
    if (stages.isEmpty()) return emptyList()
    val merged = mutableListOf<SleepStageData>()
    var current = stages[0]
    for (i in 1 until stages.size) {
        val next = stages[i]
        if (next.stageType == current.stageType) {
            current =
                current.copy(
                    endTime = next.endTime,
                    durationMinutes = current.durationMinutes + next.durationMinutes,
                )
        } else {
            merged.add(current)
            current = next
        }
    }
    merged.add(current)
    return merged
}

private fun getLabelTimestamps(
    startMs: Long,
    endMs: Long,
): List<Long> {
    val sessionDurationMinutes = (endMs - startMs) / 60_000L
    if (sessionDurationMinutes <= 0L) return emptyList()

    val durationHours = sessionDurationMinutes / 60f
    val intervalMinutes =
        when {
            durationHours <= 4 -> 60
            durationHours <= 8 -> 120
            else -> 180
        }

    val timestamps = mutableListOf<Long>()
    timestamps.add(startMs)

    val zoneId = ZoneId.systemDefault()
    val startZDT = Instant.ofEpochMilli(startMs).atZone(zoneId)
    var currentZDT =
        startZDT
            .plusHours(1)
            .withMinute(0)
            .withSecond(0)
            .withNano(0)

    while (currentZDT.toInstant().toEpochMilli() < endMs - (intervalMinutes * 30_000L)) {
        val ts = currentZDT.toInstant().toEpochMilli()
        if (ts > startMs + (intervalMinutes * 30_000L)) {
            timestamps.add(ts)
        }
        currentZDT = currentZDT.plusMinutes(intervalMinutes.toLong())
    }

    if (timestamps.last() < endMs - (15 * 60_000L)) {
        timestamps.add(endMs)
    } else if (timestamps.size > 1) {
        timestamps[timestamps.size - 1] = endMs
    } else {
        timestamps.add(endMs)
    }

    return timestamps.distinct()
}

@Composable
private fun formatStageDuration(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) {
        stringResource(R.string.sleep_duration_hours_minutes, h, m)
    } else {
        stringResource(R.string.sleep_duration_minutes_only, m)
    }
}

private data class LaneInfo(
    val stageType: String,
    val labelResId: Int,
)

private val LANES =
    listOf(
        LaneInfo(SleepStageType.AWAKE.value, R.string.sleep_stage_awake),
        LaneInfo(SleepStageType.REM.value, R.string.sleep_stage_rem),
        LaneInfo(SleepStageType.LIGHT.value, R.string.sleep_stage_light),
        LaneInfo(SleepStageType.DEEP.value, R.string.sleep_stage_deep),
    )

private data class SelectedSegmentState(
    val stage: SleepStageData,
    val segmentCenterXPx: Float,
    val segmentCenterYPx: Float,
)

@Composable
fun SleepStagesChart(
    session: SleepSessionData?,
    modifier: Modifier = Modifier,
    stageTimeline: List<SleepStageData> = emptyList(),
) {
    if (session == null) {
        CalibrationBar(
            modifier = modifier,
            color = MaterialTheme.colorScheme.surfaceVariant,
            onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val colorScheme = MaterialTheme.colorScheme
    val timeFormatter =
        remember {
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())
        }

    val sessionDurationMs = (session.endTime - session.startTime).coerceAtLeast(1L)
    val needsScroll = sessionDurationMs > NINE_HOURS_MS
    val scaleFactor = if (needsScroll) sessionDurationMs.toFloat() / NINE_HOURS_MS.toFloat() else 1f

    val labelTimestamps =
        remember(session.startTime, session.endTime) {
            getLabelTimestamps(session.startTime, session.endTime)
        }
    val sortedTimeline = remember(stageTimeline) { stageTimeline.sortedBy { it.startTime } }
    val mergedTimeline = remember(sortedTimeline) { mergeConsecutiveStages(sortedTimeline) }
    val stageDurations =
        remember(stageTimeline) {
            stageTimeline
                .groupBy { it.stageType }
                .mapValues { (_, stages) -> stages.sumOf { it.durationMinutes } }
        }

    // State refs (not `by` delegation) so that reading .value inside the Canvas draw
    // block registers the dependency there — animation frames trigger only a redraw, not
    // a full recomposition. When selectedSegment == null the values are never read, so
    // the animation clock causes no draw work at all.
    val infiniteTransition = rememberInfiniteTransition(label = "sleepPulseTransition")
    val haloAlphaState =
        infiniteTransition.animateFloat(
            initialValue = 0.15f,
            targetValue = 0.4f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(1200, easing = EaseInOutSine),
                    repeatMode = RepeatMode.Reverse,
                ),
            label = "sleepHaloAlpha",
        )
    val haloRadiusState =
        infiniteTransition.animateFloat(
            initialValue = 1.0f,
            targetValue = 1.6f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(1200, easing = EaseInOutSine),
                    repeatMode = RepeatMode.Reverse,
                ),
            label = "sleepHaloRadiusCoeff",
        )

    var selectedSegment by remember { mutableStateOf<SelectedSegmentState?>(null) }

    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Row(modifier = modifier.fillMaxWidth()) {
        // Left column: fixed-width lane labels with stage name + duration
        Column(modifier = Modifier.width(LABEL_WIDTH)) {
            LANES.forEach { lane ->
                Column(
                    modifier =
                        Modifier
                            .height(LANE_HEIGHT)
                            .fillMaxWidth(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.Start,
                ) {
                    Text(
                        text = stringResource(lane.labelResId),
                        style = MaterialTheme.typography.labelMedium,
                        color = colorScheme.onSurfaceVariant,
                    )
                    val durationMinutes = stageDurations[lane.stageType] ?: 0
                    if (durationMinutes > 0) {
                        Text(
                            text = formatStageDuration(durationMinutes),
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // Right column: chart canvas + x-axis, optionally scrollable for long sessions
        BoxWithConstraints(modifier = Modifier.weight(1f)) {
            val naturalWidth = maxWidth
            val chartWidth = if (needsScroll) naturalWidth * scaleFactor else naturalWidth

            val viewportWidthPx = constraints.maxWidth
            val tooltipData =
                remember(selectedSegment, scrollState.value, viewportWidthPx) {
                    val sel = selectedSegment ?: return@remember null
                    val viewportX = (sel.segmentCenterXPx - scrollState.value).roundToInt()
                    // Hide when the selected segment has scrolled outside the visible viewport
                    if (viewportX !in 0..viewportWidthPx) return@remember null

                    val stage = sel.stage
                    val labelResId = LANES.firstOrNull { it.stageType == stage.stageType }?.labelResId
                    val stageName = if (labelResId != null) context.getString(labelResId) else stage.stageType
                    val h = stage.durationMinutes / 60
                    val m = stage.durationMinutes % 60
                    val durationStr =
                        if (h > 0) {
                            context.getString(R.string.sleep_duration_hours_minutes, h, m)
                        } else {
                            context.getString(R.string.sleep_duration_minutes_only, m)
                        }
                    DataPointTooltipData(
                        valueText = stageName,
                        dateText = timeFormatter.format(Instant.ofEpochMilli(stage.startTime)),
                        extraLine = durationStr,
                        offset = IntOffset(viewportX, 0),
                    )
                }

            Column(
                modifier =
                    if (needsScroll) {
                        Modifier.horizontalScroll(scrollState)
                    } else {
                        Modifier
                    },
            ) {
                Canvas(
                    modifier =
                        Modifier
                            .width(chartWidth)
                            .height(CHART_TOTAL_HEIGHT)
                            .testTag("SleepStagesChartCanvas")
                            .pointerInput(mergedTimeline, session) {
                                detectTapGestures { tapOffset ->
                                    val canvasWidth = size.width.toFloat()
                                    val laneHeightPx = LANE_HEIGHT.toPx()
                                    val insetPx = SHAPE_INSET.toPx()
                                    val minW = 4.dp.toPx()
                                    val sessionDuration = (session.endTime - session.startTime).coerceAtLeast(1L)

                                    val hit =
                                        mergedTimeline.firstOrNull { stageData ->
                                            val laneIdx = getStageLaneIndex(stageData.stageType)
                                            val top = laneIdx * laneHeightPx + insetPx
                                            val height = laneHeightPx - 2 * insetPx
                                            val sx =
                                                (stageData.startTime - session.startTime).toFloat() /
                                                    sessionDuration * canvasWidth
                                            val w =
                                                (
                                                    (stageData.endTime - stageData.startTime).toFloat() /
                                                        sessionDuration * canvasWidth
                                                ).coerceAtLeast(minW)
                                            tapOffset.x in sx..(sx + w) && tapOffset.y in top..(top + height)
                                        }
                                    selectedSegment =
                                        hit?.let {
                                            val laneIdx = getStageLaneIndex(it.stageType)
                                            val sx =
                                                (it.startTime - session.startTime).toFloat() /
                                                    sessionDuration * canvasWidth
                                            val w =
                                                (
                                                    (it.endTime - it.startTime).toFloat() /
                                                        sessionDuration * canvasWidth
                                                ).coerceAtLeast(minW)
                                            SelectedSegmentState(
                                                stage = it,
                                                segmentCenterXPx = sx + w / 2f,
                                                segmentCenterYPx = (laneIdx + 0.5f) * laneHeightPx,
                                            )
                                        }
                                }
                            },
                ) {
                    val canvasWidth = size.width
                    val laneHeightPx = LANE_HEIGHT.toPx()
                    val insetPx = SHAPE_INSET.toPx()
                    val cornerPx = SHAPE_CORNER.toPx()
                    val strokePx = 1.dp.toPx()

                    if (session.endTime <= session.startTime) return@Canvas

                    // 1. Vertical grid lines for X-axis time labels
                    val gridLineColor = colorScheme.onSurface.copy(alpha = 0.05f)
                    labelTimestamps.forEach { ts ->
                        val fraction = (ts - session.startTime).toFloat() / sessionDurationMs
                        val x = fraction * canvasWidth
                        drawLine(
                            color = gridLineColor,
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = strokePx,
                        )
                    }

                    // 2. Dividers between lanes (not at top/bottom chart edges)
                    val dividerColor = colorScheme.onSurface.copy(alpha = 0.08f)
                    for (i in 1 until 4) {
                        val y = i * laneHeightPx
                        drawLine(
                            color = dividerColor,
                            start = Offset(0f, y),
                            end = Offset(canvasWidth, y),
                            strokeWidth = strokePx,
                        )
                    }

                    // 3. Stage shapes — full-height fills with 4dp vertical inset and 6dp corners
                    val minShapeWidthPx = 4.dp.toPx()
                    mergedTimeline.forEachIndexed { index, stageData ->
                        val laneIndex = getStageLaneIndex(stageData.stageType)
                        val shapeTop = laneIndex * laneHeightPx + insetPx
                        val shapeHeight = laneHeightPx - 2 * insetPx
                        val startFraction =
                            (stageData.startTime - session.startTime).toFloat() / sessionDurationMs
                        val endFraction =
                            (stageData.endTime - session.startTime).toFloat() / sessionDurationMs
                        val startX = startFraction * canvasWidth
                        val shapeWidth = ((endFraction - startFraction) * canvasWidth).coerceAtLeast(minShapeWidthPx)

                        val baseColor = getStageColor(stageData.stageType, colorScheme)

                        drawRoundRect(
                            color = baseColor,
                            topLeft = Offset(startX, shapeTop),
                            size = Size(shapeWidth, shapeHeight),
                            cornerRadius = CornerRadius(cornerPx, cornerPx),
                        )
                    }

                    // 4. Vertical connector lines at stage transitions between different lanes
                    val connectorColor = colorScheme.onSurface.copy(alpha = 0.20f)
                    for (i in 0 until mergedTimeline.size - 1) {
                        val current = mergedTimeline[i]
                        val next = mergedTimeline[i + 1]
                        val currentLaneIndex = getStageLaneIndex(current.stageType)
                        val nextLaneIndex = getStageLaneIndex(next.stageType)
                        if (currentLaneIndex != nextLaneIndex) {
                            val transitionX =
                                (current.endTime - session.startTime).toFloat() / sessionDurationMs * canvasWidth
                            val currentCenter = (currentLaneIndex + 0.5f) * laneHeightPx
                            val nextCenter = (nextLaneIndex + 0.5f) * laneHeightPx
                            drawLine(
                                color = connectorColor,
                                start = Offset(transitionX, currentCenter),
                                end = Offset(transitionX, nextCenter),
                                strokeWidth = strokePx,
                            )
                        }
                    }

                    // 5. Vertical indicator line and pulsing glow outline for selected segment.
                    // Values are read here (draw phase) so animation frames only trigger
                    // a redraw, not a full recomposition of SleepStagesChart.
                    selectedSegment?.let { sel ->
                        val selSessionDuration = (session.endTime - session.startTime).coerceAtLeast(1L)
                        val selSx =
                            (sel.stage.startTime - session.startTime).toFloat() / selSessionDuration * canvasWidth
                        val selW =
                            (
                                (sel.stage.endTime - sel.stage.startTime).toFloat() /
                                    selSessionDuration * canvasWidth
                            ).coerceAtLeast(4.dp.toPx())
                        val centerX = selSx + selW / 2f

                        // Vertical indicator line
                        drawLine(
                            color = colorScheme.primary.copy(alpha = 0.4f),
                            start = Offset(centerX, 0f),
                            end = Offset(centerX, size.height),
                            strokeWidth = 1.5.dp.toPx(),
                        )

                        val haloAlpha = haloAlphaState.value
                        val haloRadiusCoeff = haloRadiusState.value
                        val laneIdx = getStageLaneIndex(sel.stage.stageType)
                        val selTop = laneIdx * laneHeightPx + insetPx
                        val selHeight = laneHeightPx - 2 * insetPx
                        val haloPad = 3.dp.toPx() * haloRadiusCoeff
                        val stageColor = getStageColor(sel.stage.stageType, colorScheme)
                        drawRoundRect(
                            color = stageColor.copy(alpha = haloAlpha),
                            topLeft = Offset(selSx - haloPad, selTop - haloPad),
                            size = Size(selW + 2 * haloPad, selHeight + 2 * haloPad),
                            cornerRadius = CornerRadius(cornerPx + haloPad),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // X-axis time labels
                Box(modifier = Modifier.width(chartWidth).height(24.dp)) {
                    labelTimestamps.forEach { ts ->
                        val fraction = (ts - session.startTime).toFloat() / sessionDurationMs
                        Text(
                            text = timeFormatter.format(Instant.ofEpochMilli(ts)),
                            style = MaterialTheme.typography.labelSmall,
                            color = colorScheme.onSurfaceVariant,
                            modifier =
                                Modifier.layout { measurable, constraints ->
                                    val placeable = measurable.measure(constraints)
                                    layout(constraints.maxWidth, placeable.height) {
                                        val x = (fraction * constraints.maxWidth).toInt()
                                        val left =
                                            (x - placeable.width / 2).coerceIn(
                                                0,
                                                constraints.maxWidth - placeable.width,
                                            )
                                        placeable.placeRelative(left, 0)
                                    }
                                },
                        )
                    }
                }
            }

            if (tooltipData != null) {
                DataPointTooltip(
                    isVisible = true,
                    data = tooltipData,
                    onDismissRequest = { selectedSegment = null },
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun SleepStagesChartPreview() {
    val startTime = 1716159600000L // 2024-05-20 01:00:00 UTC
    val endTime = 1716188400000L // 2024-05-20 09:00:00 UTC

    val session =
        SleepSessionData(
            id = "session1",
            startTime = startTime,
            endTime = endTime,
            durationMinutes = 8 * 60,
            efficiency = 0.9f,
            deepSleepMinutes = 60,
            remSleepMinutes = 60,
            lightSleepMinutes = 300,
            awakeMinutes = 60,
            sleepScore = 85f,
            startZoneOffsetSeconds = 0,
            endZoneOffsetSeconds = 0,
            deviceName = "Preview Device",
        )

    val stageTimeline =
        listOf(
            SleepStageData(SleepStageType.AWAKE.value, startTime, startTime + 15 * 60_000L, 15),
            SleepStageData(SleepStageType.LIGHT.value, startTime + 15 * 60_000L, startTime + 60 * 60_000L, 45),
            SleepStageData(SleepStageType.DEEP.value, startTime + 60 * 60_000L, startTime + 120 * 60_000L, 60),
            SleepStageData(SleepStageType.REM.value, startTime + 120 * 60_000L, startTime + 150 * 60_000L, 30),
            SleepStageData(SleepStageType.LIGHT.value, startTime + 150 * 60_000L, startTime + 270 * 60_000L, 120),
            SleepStageData(SleepStageType.DEEP.value, startTime + 270 * 60_000L, startTime + 330 * 60_000L, 60),
            SleepStageData(SleepStageType.REM.value, startTime + 330 * 60_000L, startTime + 360 * 60_000L, 30),
            SleepStageData(SleepStageType.LIGHT.value, startTime + 360 * 60_000L, startTime + 470 * 60_000L, 110),
            SleepStageData(SleepStageType.AWAKE.value, startTime + 470 * 60_000L, startTime + 480 * 60_000L, 10),
        )

    MaterialTheme {
        SleepStagesChart(
            session = session,
            stageTimeline = stageTimeline,
            modifier = Modifier,
        )
    }
}
