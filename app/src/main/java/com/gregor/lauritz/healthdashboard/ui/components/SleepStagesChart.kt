package com.gregor.lauritz.healthdashboard.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import com.gregor.lauritz.healthdashboard.domain.model.SleepStage
import com.gregor.lauritz.healthdashboard.domain.model.SleepStageType
import com.gregor.lauritz.healthdashboard.domain.repository.SleepStageData
import com.gregor.lauritz.healthdashboard.ui.common.ChartUtils
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private fun getStageColor(
    stage: SleepStage,
    colorScheme: androidx.compose.material3.ColorScheme,
): Color =
    when (stage) {
        SleepStage.DEEP -> colorScheme.primary
        SleepStage.LIGHT -> colorScheme.tertiary.copy(alpha = 0.6f)
        SleepStage.REM -> colorScheme.tertiary
        SleepStage.AWAKE -> colorScheme.error
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
    // Find first "nice" time after start (top of the hour)
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
        // Replace last if too close to endMs
        timestamps[timestamps.size - 1] = endMs
    } else {
        timestamps.add(endMs)
    }

    return timestamps.distinct()
}

@Composable
fun SleepStagesChart(
    session: SleepSessionEntity?,
    modifier: Modifier = Modifier,
    stageTimeline: List<SleepStageData> = emptyList(),
) {
    var tooltipState by remember { mutableStateOf<DataPointTooltipData?>(null) }

    if (session == null) {
        CalibrationBar(
            modifier = modifier,
            color = MaterialTheme.colorScheme.surfaceVariant,
            onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val colorScheme = MaterialTheme.colorScheme

    Column(modifier = modifier) {
        val timeFormatter =
            remember {
                DateTimeFormatter
                    .ofLocalizedTime(FormatStyle.SHORT)
                    .withZone(ZoneId.systemDefault())
            }

        val labelTimestamps = getLabelTimestamps(session.startTime, session.endTime)
        val sortedTimeline = remember { stageTimeline.sortedBy { it.startTime } }
        val allStages = remember { SleepStage.values() }
        val sessionDurationMinutesFloat =
            remember { (session.endTime - session.startTime) / 60_000.0 }

        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .padding(horizontal = 24.dp)
                    .detectCanvasTap(
                        segments =
                            remember(sortedTimeline) {
                                sortedTimeline.mapIndexed { index, stageData ->
                                    SegmentHitBox(
                                        index = index,
                                        xStart = stageData.getStartOffsetMinutes(session.startTime).toFloat() / (session.endTime - session.startTime) * 60_000f,
                                        xEnd = (stageData.getStartOffsetMinutes(session.startTime) + stageData.durationMinutes).toFloat() / (session.endTime - session.startTime) * 60_000f,
                                        label = stageData.stageType,
                                    )
                                }
                            },
                        onSegmentTapped = { index, _ ->
                            val tappedStage = sortedTimeline[index]
                            val stageName = when (tappedStage.stageType) {
                                SleepStageType.DEEP.value -> "Deep Sleep"
                                SleepStageType.REM.value -> "REM Sleep"
                                SleepStageType.LIGHT.value -> "Light Sleep"
                                SleepStageType.AWAKE.value -> "Awake"
                                else -> tappedStage.stageType
                            }
                            val dateString = ChartUtils.formatTooltipDate(
                                Instant.ofEpochMilli(session.startTimeMs)
                                    .atZone(ZoneId.systemDefault())
                                    .toLocalDate()
                            )
                            val valueText = "$stageName: ${tappedStage.durationMinutes} min"
                            val dateText = "Date: $dateString"
                            tooltipState =
                                DataPointTooltipData(
                                    valueText = valueText,
                                    dateText = dateText,
                                )
                        },
                    ),
        ) {
            val chartWidth = size.width
            val chartHeight = size.height
            val sessionDurationMs = session.endTime - session.startTime

            if (sessionDurationMs == 0L) return@Canvas

            // Draw vertical grid lines
            val gridColor = colorScheme.outlineVariant.copy(alpha = 0.5f)
            labelTimestamps.forEach { ts ->
                val fraction = (ts - session.startTime).toFloat() / sessionDurationMs
                val x = fraction * chartWidth
                drawLine(
                    color = gridColor,
                    start = Offset(x, 0f),
                    end = Offset(x, chartHeight),
                    strokeWidth = 1.dp.toPx(),
                )
            }

            val bandGap = 8.dp.toPx()
            val bandHeight = (chartHeight - (3 * bandGap)) / 4f

            allStages.forEachIndexed { index, stage ->
                val yOffset = index * (bandHeight + bandGap)
                val color = getStageColor(stage, colorScheme)

                stageTimeline
                    .filter { it.stageType == stage.type }
                    .forEach { stageData ->
                        val startOffset = stageData.getStartOffsetMinutes(session.startTime)
                        val startX = (startOffset.toFloat() / sessionDurationMinutesFloat.toFloat()) * chartWidth
                        val width =
                            (stageData.durationMinutes.toFloat() / sessionDurationMinutesFloat.toFloat()) * chartWidth

                        drawRect(
                            color = color,
                            topLeft = Offset(startX, yOffset),
                            size = Size(width, bandHeight),
                        )
                    }
            }

            // Draw connectors between consecutive stages
            sortedTimeline.zipWithNext().forEach { (currentStage, nextStage) ->
                val currentIndex = allStages.indexOfFirst { it.type == currentStage.stageType }
                val nextIndex = allStages.indexOfFirst { it.type == nextStage.stageType }

                if (currentIndex >= 0 && nextIndex >= 0) {
                    val currentYCenter = (currentIndex) * (bandHeight + bandGap) + bandHeight / 2f
                    val nextYCenter = (nextIndex) * (bandHeight + bandGap) + bandHeight / 2f

                    val endX =
                        (
                            (
                                currentStage.getStartOffsetMinutes(session.startTime) +
                                    currentStage.durationMinutes
                            ).toFloat() / sessionDurationMinutesFloat.toFloat()
                        ) * chartWidth
                    val nextStartX =
                        (
                            nextStage.getStartOffsetMinutes(session.startTime).toFloat() /
                                sessionDurationMinutesFloat.toFloat()
                        ) * chartWidth

                    val nextStageColor = getStageColor(allStages[nextIndex], colorScheme)
                    val connectorColor = nextStageColor.copy(alpha = 0.5f)
                    val connectorStroke = 2.dp.toPx()

                    if (endX < nextStartX) {
                        val midX = (endX + nextStartX) / 2f

                        // Horizontal line from current stage end to midpoint
                        drawLine(
                            color = connectorColor,
                            start = Offset(endX, currentYCenter),
                            end = Offset(midX, currentYCenter),
                            strokeWidth = connectorStroke,
                        )

                        // Vertical line from current to next stage
                        drawLine(
                            color = connectorColor,
                            start = Offset(midX, currentYCenter),
                            end = Offset(midX, nextYCenter),
                            strokeWidth = connectorStroke,
                        )

                        // Horizontal line from midpoint to next stage start
                        drawLine(
                            color = connectorColor,
                            start = Offset(midX, nextYCenter),
                            end = Offset(nextStartX, nextYCenter),
                            strokeWidth = connectorStroke,
                        )
                    }
                }
            }
        }

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 8.dp)
                    .height(24.dp),
        ) {
            val sessionDurationMs = session.endTime - session.startTime
            labelTimestamps.forEach { ts ->
                val fraction = (ts - session.startTime).toFloat() / sessionDurationMs
                Text(
                    text = timeFormatter.format(Instant.ofEpochMilli(ts)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier.layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            layout(constraints.maxWidth, placeable.height) {
                                val x = (fraction * constraints.maxWidth).toInt()
                                placeable.placeRelative(x - placeable.width / 2, 0)
                            }
                        },
                )
            }
        }
    }

    if (tooltipState != null) {
        DataPointTooltip(
            isVisible = true,
            data = tooltipState!!,
            onDismissRequest = { tooltipState = null },
        )
    }
}

@Preview(showBackground = true)
@Composable
fun SleepStagesChartPreview() {
    val startTime = 1716159600000L // 2024-05-20 01:00:00 UTC
    val endTime = 1716188400000L // 2024-05-20 09:00:00 UTC

    val session =
        SleepSessionEntity(
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
            modifier = Modifier.padding(16.dp),
        )
    }
}
