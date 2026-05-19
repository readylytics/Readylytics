package com.gregor.lauritz.healthdashboard.ui.components

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

enum class SleepStage(val label: String) {
    DEEP("Deep"),
    LIGHT("Light"),
    REM("REM"),
    AWAKE("Awake"),
}

private data class StageSegment(
    val stage: SleepStage,
    val startMinute: Int,
    val durationMinutes: Int,
)

@Composable
fun SleepStagesChart(
    session: SleepSessionEntity?,
    modifier: Modifier = Modifier,
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
    val deepColor = colorScheme.primary
    val lightColor = colorScheme.tertiary.copy(alpha = 0.6f)
    val remColor = colorScheme.tertiary
    val awakeColor = colorScheme.error

    Column(modifier = modifier) {
        val timeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            .withZone(ZoneId.systemDefault())
        val startInstant = Instant.ofEpochMilli(session.startTime)
        val startLabel = timeFormatter.format(startInstant)
        val endInstant = Instant.ofEpochMilli(session.endTime)
        val endLabel = timeFormatter.format(endInstant)

        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .padding(horizontal = 16.dp),
        ) {
            val chartWidth = size.width
            val chartHeight = size.height
            val totalMinutes =
                session.deepSleepMinutes + session.remSleepMinutes +
                    session.lightSleepMinutes + session.awakeMinutes

            if (totalMinutes == 0) return@Canvas

            val bandHeight = chartHeight / 4f
            val bandGap = 8.dp.toPx()

            val stageSegments = generateSleepSegments(
                deepMinutes = session.deepSleepMinutes,
                remMinutes = session.remSleepMinutes,
                lightMinutes = session.lightSleepMinutes,
                awakeMinutes = session.awakeMinutes,
            )

            val stages = listOf(
                Triple(SleepStage.DEEP, deepColor),
                Triple(SleepStage.LIGHT, lightColor),
                Triple(SleepStage.REM, remColor),
                Triple(SleepStage.AWAKE, awakeColor),
            )

            stages.forEachIndexed { index, (stage, color) ->
                val yOffset = index * (bandHeight + bandGap)

                stageSegments
                    .filter { it.stage == stage }
                    .forEach { segment ->
                        val startX =
                            (segment.startMinute.toFloat() / totalMinutes) * chartWidth
                        val width =
                            (segment.durationMinutes.toFloat() / totalMinutes) * chartWidth

                        drawRect(
                            color = color,
                            topLeft = Offset(startX, yOffset),
                            size = Size(width, bandHeight),
                        )
                    }
            }
        }

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = startLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(modifier = Modifier.weight(1f))
            Text(
                text = endLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun generateSleepSegments(
    deepMinutes: Int,
    remMinutes: Int,
    lightMinutes: Int,
    awakeMinutes: Int,
): List<StageSegment> {
    val segments = mutableListOf<StageSegment>()
    val totalMinutes = deepMinutes + remMinutes + lightMinutes + awakeMinutes

    if (totalMinutes == 0) return segments

    var currentMinute = 0

    segments.add(
        StageSegment(
            stage = SleepStage.REM,
            startMinute = currentMinute,
            durationMinutes = remMinutes,
        )
    )
    currentMinute += remMinutes

    segments.add(
        StageSegment(
            stage = SleepStage.DEEP,
            startMinute = currentMinute,
            durationMinutes = deepMinutes,
        )
    )
    currentMinute += deepMinutes

    segments.add(
        StageSegment(
            stage = SleepStage.LIGHT,
            startMinute = currentMinute,
            durationMinutes = lightMinutes,
        )
    )
    currentMinute += lightMinutes

    if (awakeMinutes > 0) {
        segments.add(
            StageSegment(
                stage = SleepStage.AWAKE,
                startMinute = currentMinute,
                durationMinutes = awakeMinutes,
            )
        )
    }

    return segments
}
