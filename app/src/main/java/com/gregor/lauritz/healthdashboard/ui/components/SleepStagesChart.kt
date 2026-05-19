package com.gregor.lauritz.healthdashboard.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import com.gregor.lauritz.healthdashboard.domain.model.SleepStage
import com.gregor.lauritz.healthdashboard.domain.repository.SleepStageData
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

private fun calculateLabelTimes(
    startMs: Long,
    endMs: Long,
    sessionDurationMinutes: Long,
): List<String> {
    if (sessionDurationMinutes <= 0L) return emptyList()

    val labels = mutableListOf<String>()
    val durationHours = sessionDurationMinutes / 60f

    val intervalMinutes = when {
        durationHours <= 4 -> 60
        durationHours <= 8 -> 120
        else -> 180
    }

    val timeFormatter =
        DateTimeFormatter
            .ofPattern("HH:mm")
            .withZone(ZoneId.systemDefault())

    var currentTime = startMs
    while (currentTime < endMs) {
        val timeStr = timeFormatter.format(Instant.ofEpochMilli(currentTime))
        labels.add(timeStr)
        currentTime += intervalMinutes * 60_000L
    }

    return labels
}

@Composable
fun SleepStagesChart(
    session: SleepSessionEntity?,
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

    Column(modifier = modifier) {
        val timeFormatter =
            DateTimeFormatter
                .ofLocalizedTime(FormatStyle.SHORT)
                .withZone(ZoneId.systemDefault())
        val startInstant = Instant.ofEpochMilli(session.startTime)
        val startLabel = timeFormatter.format(startInstant)
        val endInstant = Instant.ofEpochMilli(session.endTime)
        val endLabel = timeFormatter.format(endInstant)

        val sessionDurationMinutes = (session.endTime - session.startTime) / 60_000L
        val intermediateLabels =
            calculateLabelTimes(session.startTime, session.endTime, sessionDurationMinutes)

        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .padding(horizontal = 16.dp),
        ) {
            val chartWidth = size.width
            val chartHeight = size.height

            // Scale based on actual session duration (not just stage data)
            val sessionDurationMinutes = (session.endTime - session.startTime) / 60_000L

            if (sessionDurationMinutes == 0L) return@Canvas

            val bandGap = 8.dp.toPx()
            val bandHeight = (chartHeight - (3 * bandGap)) / 4f

            SleepStage.values().forEachIndexed { index, stage ->
                val yOffset = index * (bandHeight + bandGap)
                val color = getStageColor(stage, colorScheme)

                stageTimeline
                    .filter { it.stageType == stage.type }
                    .forEach { stageData ->
                        val startOffset = stageData.getStartOffsetMinutes(session.startTime)
                        val startX = (startOffset.toFloat() / sessionDurationMinutes) * chartWidth
                        val width = (stageData.durationMinutes.toFloat() / sessionDurationMinutes) * chartWidth

                        drawRect(
                            color = color,
                            topLeft = Offset(startX, yOffset),
                            size = Size(width, bandHeight),
                        )
                    }
            }

            // Draw connectors between consecutive stages
            stageTimeline.sortedBy { it.startTime }.zipWithNext().forEach { (currentStage, nextStage) ->
                val currentIndex = SleepStage.values().indexOfFirst { it.type == currentStage.stageType }
                val nextIndex = SleepStage.values().indexOfFirst { it.type == nextStage.stageType }

                if (currentIndex >= 0 && nextIndex >= 0) {
                    val currentYCenter = (currentIndex) * (bandHeight + bandGap) + bandHeight / 2f
                    val nextYCenter = (nextIndex) * (bandHeight + bandGap) + bandHeight / 2f

                    val endX =
                        ((currentStage.getStartOffsetMinutes(session.startTime) +
                            currentStage.durationMinutes)
                            .toFloat() / sessionDurationMinutes) * chartWidth
                    val nextStartX =
                        (nextStage.getStartOffsetMinutes(session.startTime).toFloat() /
                            sessionDurationMinutes) * chartWidth

                    val nextStageColor = getStageColor(SleepStage.values()[nextIndex], colorScheme)
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

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Text(
                text = startLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            intermediateLabels.forEach { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = endLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
