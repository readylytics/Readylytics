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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import com.gregor.lauritz.healthdashboard.domain.repository.SleepStageData
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

enum class SleepStage(
    val label: String,
) {
    DEEP("Deep"),
    LIGHT("Light"),
    REM("REM"),
    AWAKE("Awake"),
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
    val deepColor = colorScheme.primary
    val lightColor = colorScheme.tertiary.copy(alpha = 0.6f)
    val remColor = colorScheme.tertiary
    val awakeColor = colorScheme.error

    Column(modifier = modifier) {
        val timeFormatter =
            DateTimeFormatter
                .ofLocalizedTime(FormatStyle.SHORT)
                .withZone(ZoneId.systemDefault())
        val startInstant = Instant.ofEpochMilli(session.startTime)
        val startLabel = timeFormatter.format(startInstant)
        val endInstant = Instant.ofEpochMilli(session.endTime)
        val endLabel = timeFormatter.format(endInstant)

        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .padding(horizontal = 16.dp),
        ) {
            val chartWidth = size.width
            val chartHeight = size.height
            val totalMinutes =
                session.deepSleepMinutes + session.remSleepMinutes +
                    session.lightSleepMinutes + session.awakeMinutes

            if (totalMinutes == 0) return@Canvas

            val bandGap = 8.dp.toPx()
            val bandHeight = (chartHeight - (3 * bandGap)) / 4f

            val stages: List<Pair<SleepStage, Color>> =
                listOf(
                    Pair(SleepStage.DEEP, deepColor),
                    Pair(SleepStage.LIGHT, lightColor),
                    Pair(SleepStage.REM, remColor),
                    Pair(SleepStage.AWAKE, awakeColor),
                )

            stages.forEachIndexed { index, (stage, color) ->
                val yOffset = index * (bandHeight + bandGap)

                stageTimeline
                    .filter { it.stageType == stage.label.uppercase() }
                    .forEach { stageData ->
                        val startOffset = stageData.getStartOffsetMinutes(session.startTime)
                        val startX = (startOffset.toFloat() / totalMinutes) * chartWidth
                        val width = (stageData.durationMinutes.toFloat() / totalMinutes) * chartWidth

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
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
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
