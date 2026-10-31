package app.readylytics.health.feature.sleep

import androidx.compose.ui.geometry.Offset
import app.readylytics.health.core.model.domain.repository.SleepSessionData
import app.readylytics.health.core.model.domain.repository.SleepStageData

internal data class SelectedSegmentState(
    val stage: SleepStageData,
    val segmentCenterXPx: Float,
    val segmentCenterYPx: Float,
)

/** Horizontal extent of one stage on the canvas. */
internal data class StageSpan(
    val startX: Float,
    val width: Float,
) {
    val centerX: Float = startX + width / 2f
}

/**
 * Pixel geometry of the stage lanes for a canvas [canvasWidth] px wide. Hit testing, accessibility
 * selection and the selection halo all take a stage's extent from here, so they cannot drift apart.
 */
internal class StageGeometry(
    private val session: SleepSessionData,
    private val canvasWidth: Float,
    private val laneHeightPx: Float,
    private val insetPx: Float,
    private val minWidthPx: Float,
) {
    /** Horizontal extent of [stage], never narrower than the minimum shape width. */
    fun spanOf(stage: SleepStageData): StageSpan {
        val sessionDuration = (session.endTime - session.startTime).coerceAtLeast(1L)
        val startX = (stage.startTime - session.startTime).toFloat() / sessionDuration * canvasWidth
        val width =
            ((stage.endTime - stage.startTime).toFloat() / sessionDuration * canvasWidth).coerceAtLeast(minWidthPx)
        return StageSpan(startX, width)
    }

    fun segmentOf(
        stage: SleepStageData,
        span: StageSpan = spanOf(stage),
    ): SelectedSegmentState =
        SelectedSegmentState(
            stage = stage,
            segmentCenterXPx = span.centerX,
            segmentCenterYPx = (getStageLaneIndex(stage.stageType) + 0.5f) * laneHeightPx,
        )

    /** The first stage of [timeline] under [touch], as a selection. */
    fun segmentAt(
        touch: Offset,
        timeline: List<SleepStageData>,
    ): SelectedSegmentState? {
        val shapeHeight = laneHeightPx - 2 * insetPx
        return timeline.firstNotNullOfOrNull { stage ->
            val span = spanOf(stage)
            val top = getStageLaneIndex(stage.stageType) * laneHeightPx + insetPx
            val hit = touch.x in span.startX..(span.startX + span.width) && touch.y in top..(top + shapeHeight)
            if (hit) segmentOf(stage, span) else null
        }
    }
}
