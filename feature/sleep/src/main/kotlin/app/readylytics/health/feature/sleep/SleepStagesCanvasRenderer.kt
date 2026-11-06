package app.readylytics.health.feature.sleep

import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.model.domain.model.SleepStageType
import app.readylytics.health.core.model.domain.repository.SleepSessionData
import app.readylytics.health.core.model.domain.repository.SleepStageData

internal val LANE_HEIGHT = 56.dp
internal val SHAPE_INSET = 4.dp
internal val SHAPE_CORNER = 6.dp
internal val MIN_SHAPE_WIDTH = 4.dp
internal val LABEL_WIDTH = 64.dp
internal val CHART_TOTAL_HEIGHT = LANE_HEIGHT * 4
internal const val NINE_HOURS_MS = 9L * 3_600_000L

internal const val AWAKE_LANE_INDEX = 0
internal const val REM_LANE_INDEX = 1
internal const val LIGHT_LANE_INDEX = 2
internal const val DEEP_LANE_INDEX = 3

internal data class LaneInfo(
    val stageType: String,
    val labelResId: Int,
)

internal val LANES =
    listOf(
        LaneInfo(SleepStageType.AWAKE.value, R.string.sleep_stage_awake),
        LaneInfo(SleepStageType.REM.value, R.string.sleep_stage_rem),
        LaneInfo(SleepStageType.LIGHT.value, R.string.sleep_stage_light),
        LaneInfo(SleepStageType.DEEP.value, R.string.sleep_stage_deep),
    )

internal fun getStageLaneIndex(stageType: String): Int =
    when (stageType) {
        SleepStageType.AWAKE.value -> AWAKE_LANE_INDEX
        SleepStageType.REM.value -> REM_LANE_INDEX
        SleepStageType.LIGHT.value -> LIGHT_LANE_INDEX
        SleepStageType.DEEP.value -> DEEP_LANE_INDEX
        else -> LIGHT_LANE_INDEX
    }

internal fun getStageColor(
    stageType: String,
    colorScheme: ColorScheme,
): Color =
    when (stageType) {
        SleepStageType.AWAKE.value -> colorScheme.error.copy(alpha = 0.80f)
        SleepStageType.REM.value -> colorScheme.secondary.copy(alpha = 0.80f)
        SleepStageType.LIGHT.value -> colorScheme.primary.copy(alpha = 0.60f)
        SleepStageType.DEEP.value -> colorScheme.primary
        else -> colorScheme.primary.copy(alpha = 0.60f)
    }

internal fun mergeConsecutiveStages(stages: List<SleepStageData>): List<SleepStageData> {
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

internal class SleepStagesPulseAnimation(
    val haloRadiusCoeff: State<Float>,
    val haloAlpha: State<Float>,
)

@Composable
internal fun rememberSleepStagesPulseAnimation(): SleepStagesPulseAnimation {
    val infiniteTransition = rememberInfiniteTransition(label = "sleepPulseTransition")
    val haloAlpha =
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
    val haloRadiusCoeff =
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
    return remember(infiniteTransition) { SleepStagesPulseAnimation(haloRadiusCoeff, haloAlpha) }
}

internal data class SleepStagesRenderDimensions(
    val canvasWidth: Float,
    val laneHeightPx: Float,
    val insetPx: Float,
    val cornerPx: Float,
    val strokePx: Float,
    val minShapeWidthPx: Float,
)

internal fun DrawScope.renderSleepStagesCanvas(
    session: SleepSessionData,
    mergedTimeline: List<SleepStageData>,
    labelTimestamps: List<Long>,
    sessionDurationMs: Long,
    selectedSegment: SelectedSegmentState?,
    pulse: SleepStagesPulseAnimation,
    colorScheme: ColorScheme,
) {
    if (session.endTime <= session.startTime) return

    val dims =
        SleepStagesRenderDimensions(
            canvasWidth = size.width,
            laneHeightPx = LANE_HEIGHT.toPx(),
            insetPx = SHAPE_INSET.toPx(),
            cornerPx = SHAPE_CORNER.toPx(),
            strokePx = 1.dp.toPx(),
            minShapeWidthPx = MIN_SHAPE_WIDTH.toPx(),
        )

    // 1. Vertical grid lines for X-axis time labels
    drawSleepStagesGridLines(
        labelTimestamps = labelTimestamps,
        sessionStartTime = session.startTime,
        sessionDurationMs = sessionDurationMs,
        dims = dims,
        color = colorScheme.onSurface.copy(alpha = 0.05f),
    )

    // 2. Dividers between lanes (not at top/bottom chart edges)
    drawSleepStagesLaneDividers(
        dims = dims,
        color = colorScheme.onSurface.copy(alpha = 0.08f),
    )

    // 3. Stage shapes — full-height fills with 4dp vertical inset and 6dp corners
    drawSleepStagesShapes(
        mergedTimeline = mergedTimeline,
        sessionStartTime = session.startTime,
        sessionDurationMs = sessionDurationMs,
        dims = dims,
        colorScheme = colorScheme,
    )

    // 4. Vertical connector lines at stage transitions between different lanes
    drawSleepStagesConnectors(
        mergedTimeline = mergedTimeline,
        sessionStartTime = session.startTime,
        sessionDurationMs = sessionDurationMs,
        dims = dims,
        color = colorScheme.onSurface.copy(alpha = 0.20f),
    )

    // 5. Vertical indicator line and pulsing glow outline for selected segment.
    drawSleepStagesSelection(
        selectedSegment = selectedSegment,
        session = session,
        dims = dims,
        pulse = pulse,
        colorScheme = colorScheme,
    )
}

private fun DrawScope.drawSleepStagesGridLines(
    labelTimestamps: List<Long>,
    sessionStartTime: Long,
    sessionDurationMs: Long,
    dims: SleepStagesRenderDimensions,
    color: Color,
) {
    labelTimestamps.forEach { ts ->
        val fraction = (ts - sessionStartTime).toFloat() / sessionDurationMs
        val x = fraction * dims.canvasWidth
        drawLine(
            color = color,
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = dims.strokePx,
        )
    }
}

private fun DrawScope.drawSleepStagesLaneDividers(
    dims: SleepStagesRenderDimensions,
    color: Color,
) {
    for (i in 1 until 4) {
        val y = i * dims.laneHeightPx
        drawLine(
            color = color,
            start = Offset(0f, y),
            end = Offset(dims.canvasWidth, y),
            strokeWidth = dims.strokePx,
        )
    }
}

private fun DrawScope.drawSleepStagesShapes(
    mergedTimeline: List<SleepStageData>,
    sessionStartTime: Long,
    sessionDurationMs: Long,
    dims: SleepStagesRenderDimensions,
    colorScheme: ColorScheme,
) {
    val shapeHeight = dims.laneHeightPx - 2 * dims.insetPx
    mergedTimeline.forEach { stageData ->
        val laneIndex = getStageLaneIndex(stageData.stageType)
        val shapeTop = laneIndex * dims.laneHeightPx + dims.insetPx
        val startFraction = (stageData.startTime - sessionStartTime).toFloat() / sessionDurationMs
        val endFraction = (stageData.endTime - sessionStartTime).toFloat() / sessionDurationMs
        val startX = startFraction * dims.canvasWidth
        val shapeWidth =
            ((endFraction - startFraction) * dims.canvasWidth).coerceAtLeast(dims.minShapeWidthPx)
        val baseColor = getStageColor(stageData.stageType, colorScheme)

        drawRoundRect(
            color = baseColor,
            topLeft = Offset(startX, shapeTop),
            size = Size(shapeWidth, shapeHeight),
            cornerRadius = CornerRadius(dims.cornerPx, dims.cornerPx),
        )
    }
}

private fun DrawScope.drawSleepStagesConnectors(
    mergedTimeline: List<SleepStageData>,
    sessionStartTime: Long,
    sessionDurationMs: Long,
    dims: SleepStagesRenderDimensions,
    color: Color,
) {
    for (i in 0 until mergedTimeline.size - 1) {
        val current = mergedTimeline[i]
        val next = mergedTimeline[i + 1]
        val currentLaneIndex = getStageLaneIndex(current.stageType)
        val nextLaneIndex = getStageLaneIndex(next.stageType)
        if (currentLaneIndex != nextLaneIndex) {
            val transitionX =
                (current.endTime - sessionStartTime).toFloat() / sessionDurationMs * dims.canvasWidth
            val currentCenter = (currentLaneIndex + 0.5f) * dims.laneHeightPx
            val nextCenter = (nextLaneIndex + 0.5f) * dims.laneHeightPx
            drawLine(
                color = color,
                start = Offset(transitionX, currentCenter),
                end = Offset(transitionX, nextCenter),
                strokeWidth = dims.strokePx,
            )
        }
    }
}

private fun DrawScope.drawSleepStagesSelection(
    selectedSegment: SelectedSegmentState?,
    session: SleepSessionData,
    dims: SleepStagesRenderDimensions,
    pulse: SleepStagesPulseAnimation,
    colorScheme: ColorScheme,
) {
    val sel = selectedSegment ?: return
    val span =
        StageGeometry(
            session = session,
            canvasWidth = dims.canvasWidth,
            laneHeightPx = dims.laneHeightPx,
            insetPx = dims.insetPx,
            minWidthPx = dims.minShapeWidthPx,
        ).spanOf(sel.stage)
    val selSx = span.startX
    val selW = span.width
    val centerX = span.centerX

    // Vertical indicator line
    drawLine(
        color = colorScheme.primary.copy(alpha = 0.4f),
        start = Offset(centerX, 0f),
        end = Offset(centerX, size.height),
        strokeWidth = 1.5.dp.toPx(),
    )

    val haloAlpha = pulse.haloAlpha.value
    val haloRadiusCoeff = pulse.haloRadiusCoeff.value
    val laneIdx = getStageLaneIndex(sel.stage.stageType)
    val selTop = laneIdx * dims.laneHeightPx + dims.insetPx
    val selHeight = dims.laneHeightPx - 2 * dims.insetPx
    val haloPad = 3.dp.toPx() * haloRadiusCoeff
    val stageColor = getStageColor(sel.stage.stageType, colorScheme)
    drawRoundRect(
        color = stageColor.copy(alpha = haloAlpha),
        topLeft = Offset(selSx - haloPad, selTop - haloPad),
        size = Size(selW + 2 * haloPad, selHeight + 2 * haloPad),
        cornerRadius = CornerRadius(dims.cornerPx + haloPad),
    )
}
