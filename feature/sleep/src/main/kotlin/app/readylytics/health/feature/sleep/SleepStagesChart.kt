package app.readylytics.health.feature.sleep

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.model.domain.model.SleepStageType
import app.readylytics.health.core.model.domain.repository.SleepSessionData
import app.readylytics.health.core.model.domain.repository.SleepStageData
import app.readylytics.health.core.ui.components.DataPointTooltip
import app.readylytics.health.core.ui.components.detectLongPressSelection
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun SleepStagesChart(
    session: SleepSessionData?,
    modifier: Modifier = Modifier,
    stageTimeline: List<SleepStageData> = emptyList(),
) {
    if (session == null) {
        CalibrationBar(
            modifier = modifier,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
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

    val pulse = rememberSleepStagesPulseAnimation()
    val selectedSegmentState = remember { mutableStateOf<SelectedSegmentState?>(null) }
    var selectedSegment by selectedSegmentState
    val hapticFeedback = LocalHapticFeedback.current

    LaunchedEffect(session) { selectedSegment = null }

    val selectedStageName = selectedSegment?.let { stageDisplayName(it.stage.stageType) }
    val selectedDurationStr = selectedSegment?.let { formatStageDuration(it.stage.durationMinutes) }
    val scrollState = rememberScrollState()

    Row(modifier = modifier.fillMaxWidth()) {
        SleepStagesLaneLabels(stageDurations = stageDurations, colorScheme = colorScheme)

        BoxWithConstraints(modifier = Modifier.weight(1f)) {
            val naturalWidth = maxWidth
            val chartWidth = if (needsScroll) naturalWidth * scaleFactor else naturalWidth
            val tooltipData by rememberSleepStagesTooltipData(
                selectedSegment = selectedSegment,
                viewportWidthPx = constraints.maxWidth,
                scrollOffsetPx = scrollState.value,
                selectedStageName = selectedStageName,
                selectedDurationStr = selectedDurationStr,
                timeFormatter = timeFormatter,
            )

            val customActionsList =
                rememberSleepStagesCustomActions(
                    selectedSegment = selectedSegment,
                    mergedTimeline = mergedTimeline,
                    session = session,
                    chartWidth = chartWidth,
                    onSelectSegment = { selectedSegment = it },
                )
            val chartSummary = stringResource(R.string.chart_accessibility_sleep_stages_summary)
            val selectedValueDescription =
                rememberSleepStagesStateDescription(
                    selectedSegment = selectedSegment,
                    timeFormatter = timeFormatter,
                    selectedStageName = selectedStageName,
                    selectedDurationStr = selectedDurationStr,
                )

            val scrollModifier = if (needsScroll) Modifier.horizontalScroll(scrollState) else Modifier
            Column(modifier = scrollModifier) {
                Canvas(
                    modifier =
                        Modifier
                            .width(chartWidth)
                            .height(CHART_TOTAL_HEIGHT)
                            .testTag("SleepStagesChartCanvas")
                            .semantics {
                                contentDescription = chartSummary
                                stateDescription = selectedValueDescription
                                customActions = customActionsList
                            }.pointerInput(mergedTimeline, session) {
                                detectLongPressSelection(hapticFeedback, selectedSegmentState) { touch ->
                                    StageGeometry(
                                        session = session,
                                        canvasWidth = size.width.toFloat(),
                                        laneHeightPx = LANE_HEIGHT.toPx(),
                                        insetPx = SHAPE_INSET.toPx(),
                                        minWidthPx = MIN_SHAPE_WIDTH.toPx(),
                                    ).segmentAt(touch, mergedTimeline)
                                }
                            },
                ) {
                    renderSleepStagesCanvas(
                        session = session,
                        mergedTimeline = mergedTimeline,
                        labelTimestamps = labelTimestamps,
                        sessionDurationMs = sessionDurationMs,
                        selectedSegment = selectedSegment,
                        pulse = pulse,
                        colorScheme = colorScheme,
                    )
                }

                Spacer(modifier = Modifier.height(MaterialTheme.spacing.small))

                SleepStagesXAxis(
                    labelTimestamps = labelTimestamps,
                    timeFormatter = timeFormatter,
                    sessionStartTime = session.startTime,
                    sessionDurationMs = sessionDurationMs,
                    chartWidth = chartWidth,
                    colorScheme = colorScheme,
                )
            }

            tooltipData?.let { data ->
                DataPointTooltip(
                    isVisible = true,
                    data = data,
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
            deviceName = "Preview Device",
            startTime = startTime,
            endTime = endTime,
            durationMinutes = 8 * 60,
            efficiency = 0.9f,
            deepSleepMinutes = 60,
            lightSleepMinutes = 300,
            remSleepMinutes = 60,
            awakeMinutes = 60,
            sleepScore = 85f,
            startZoneOffsetSeconds = 0,
            endZoneOffsetSeconds = 0,
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
        SleepStagesChart(session = session, stageTimeline = stageTimeline)
    }
}
