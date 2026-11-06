package app.readylytics.health.feature.sleep

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import app.readylytics.health.core.model.domain.repository.SleepSessionData
import app.readylytics.health.core.model.domain.repository.SleepStageData
import app.readylytics.health.core.ui.components.DataPointTooltipData
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import app.readylytics.health.core.ui.R as CoreUiR

internal fun buildSleepStagesAccessibilityActions(
    mergedTimeline: List<SleepStageData>,
    geometry: StageGeometry,
    selectedSegment: SelectedSegmentState?,
    prevActionLabel: String,
    nextActionLabel: String,
    clearActionLabel: String,
    onSelectSegment: (SelectedSegmentState?) -> Unit,
): List<CustomAccessibilityAction> {
    val list = mutableListOf<CustomAccessibilityAction>()
    if (mergedTimeline.isNotEmpty()) {
        fun selectIndex(idx: Int) {
            onSelectSegment(geometry.segmentOf(mergedTimeline[idx]))
        }

        list.add(
            CustomAccessibilityAction(prevActionLabel) {
                val currentStage = selectedSegment?.stage
                val currentIndex = mergedTimeline.indexOfFirst { it.startTime == currentStage?.startTime }
                if (currentIndex > 0) {
                    selectIndex(currentIndex - 1)
                    true
                } else {
                    selectIndex(mergedTimeline.lastIndex)
                    true
                }
            },
        )
        list.add(
            CustomAccessibilityAction(nextActionLabel) {
                val currentStage = selectedSegment?.stage
                val currentIndex = mergedTimeline.indexOfFirst { it.startTime == currentStage?.startTime }
                val lastIdx = mergedTimeline.lastIndex
                if (currentIndex != -1 && currentIndex < lastIdx) {
                    selectIndex(currentIndex + 1)
                    true
                } else {
                    selectIndex(0)
                    true
                }
            },
        )
    }
    if (selectedSegment != null) {
        list.add(
            CustomAccessibilityAction(clearActionLabel) {
                onSelectSegment(null)
                true
            },
        )
    }
    return list
}

@Composable
internal fun rememberSleepStagesCustomActions(
    selectedSegment: SelectedSegmentState?,
    mergedTimeline: List<SleepStageData>,
    session: SleepSessionData,
    chartWidth: Dp,
    onSelectSegment: (SelectedSegmentState?) -> Unit,
): List<CustomAccessibilityAction> {
    val density = LocalDensity.current
    val prevActionLabel = stringResource(CoreUiR.string.action_previous_point)
    val nextActionLabel = stringResource(CoreUiR.string.action_next_point)
    val clearActionLabel = stringResource(CoreUiR.string.action_clear_selection)
    return remember(selectedSegment, mergedTimeline, session, chartWidth, density) {
        val geometry =
            with(density) {
                StageGeometry(
                    session = session,
                    canvasWidth = chartWidth.toPx(),
                    laneHeightPx = LANE_HEIGHT.toPx(),
                    insetPx = SHAPE_INSET.toPx(),
                    minWidthPx = MIN_SHAPE_WIDTH.toPx(),
                )
            }
        buildSleepStagesAccessibilityActions(
            mergedTimeline = mergedTimeline,
            geometry = geometry,
            selectedSegment = selectedSegment,
            prevActionLabel = prevActionLabel,
            nextActionLabel = nextActionLabel,
            clearActionLabel = clearActionLabel,
            onSelectSegment = onSelectSegment,
        )
    }
}

@Composable
internal fun rememberSleepStagesStateDescription(
    selectedSegment: SelectedSegmentState?,
    timeFormatter: DateTimeFormatter,
    selectedStageName: String?,
    selectedDurationStr: String?,
): String =
    selectedSegment?.let { sel ->
        val timeStr = timeFormatter.format(Instant.ofEpochMilli(sel.stage.startTime))
        stringResource(
            CoreUiR.string.chart_accessibility_selected_sleep_stage,
            selectedStageName.orEmpty(),
            selectedDurationStr.orEmpty(),
            timeStr,
        )
    } ?: stringResource(CoreUiR.string.chart_accessibility_no_selection)

internal fun sleepStagesTooltipData(
    selectedSegment: SelectedSegmentState?,
    viewportWidthPx: Int,
    scrollOffsetPx: Int,
    selectedStageName: String?,
    selectedDurationStr: String?,
    timeFormatter: DateTimeFormatter,
): DataPointTooltipData? {
    val sel = selectedSegment ?: return null
    val viewportX = (sel.segmentCenterXPx - scrollOffsetPx).roundToInt()
    if (viewportX !in 0..viewportWidthPx) return null
    return DataPointTooltipData(
        valueText = selectedStageName.orEmpty(),
        dateText = timeFormatter.format(Instant.ofEpochMilli(sel.stage.startTime)),
        extraLine = selectedDurationStr,
        offset = IntOffset(viewportX, 0),
    )
}

/** [scrollOffsetPx] is a lambda so the scroll value is read inside `derivedStateOf`, not during composition. */
@Composable
internal fun rememberSleepStagesTooltipData(
    selectedSegment: SelectedSegmentState?,
    viewportWidthPx: Int,
    scrollOffsetPx: () -> Int,
    selectedStageName: String?,
    selectedDurationStr: String?,
    timeFormatter: DateTimeFormatter,
): State<DataPointTooltipData?> {
    val currentScrollOffset by rememberUpdatedState(scrollOffsetPx)
    return remember(selectedSegment, viewportWidthPx, selectedStageName, selectedDurationStr) {
        derivedStateOf {
            sleepStagesTooltipData(
                selectedSegment,
                viewportWidthPx,
                currentScrollOffset(),
                selectedStageName,
                selectedDurationStr,
                timeFormatter,
            )
        }
    }
}
