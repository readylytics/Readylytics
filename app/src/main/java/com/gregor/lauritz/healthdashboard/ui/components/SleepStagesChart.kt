package com.gregor.lauritz.healthdashboard.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class SleepStage(val label: String) {
    AWAKE("Awake"),
    REM("REM"),
    LIGHT("Light"),
    DEEP("Deep"),
}

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
    val awakeColor = colorScheme.error
    val remColor = colorScheme.tertiary
    val lightColor = remColor.copy(alpha = 0.6f)
    val deepColor = colorScheme.primary

    Column(modifier = modifier) {
        val timeLabels = generateTimeLabels(session.startTime, session.endTime)

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .padding(horizontal = 16.dp),
        ) {
            val chartWidth = size.width
            val chartHeight = size.height

            val totalMinutes = session.deepSleepMinutes + session.remSleepMinutes +
                session.lightSleepMinutes + session.awakeMinutes
            if (totalMinutes == 0) return@Canvas

            val bandHeight = chartHeight / 4f
            val bandGap = 8.dp.toPx()

            val stageBlocks = generateSleepBlocks(
                awakeMinutes = session.awakeMinutes,
                remMinutes = session.remSleepMinutes,
                lightMinutes = session.lightSleepMinutes,
                deepMinutes = session.deepSleepMinutes,
                totalMinutes = totalMinutes,
                chartWidth = chartWidth,
            )

            val stages = listOf(
                Triple(SleepStage.AWAKE, session.awakeMinutes, awakeColor),
                Triple(SleepStage.REM, session.remSleepMinutes, remColor),
                Triple(SleepStage.LIGHT, session.lightSleepMinutes, lightColor),
                Triple(SleepStage.DEEP, session.deepSleepMinutes, deepColor),
            )

            stages.forEachIndexed { index, (stage, minutes, color) ->
                val yOffset = index * (bandHeight + bandGap)

                val blocksForStage = stageBlocks[stage] ?: emptyList()
                blocksForStage.forEach { block ->
                    drawRect(
                        color = color,
                        topLeft = Offset(block.startX, yOffset),
                        size = androidx.compose.ui.geometry.Size(block.width, bandHeight),
                    )
                }
            }

            timeLabels.forEach { label ->
                val xPos = (label.percentage / 100f) * chartWidth
                drawLine(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    start = Offset(xPos, 0f),
                    end = Offset(xPos, chartHeight),
                    strokeWidth = 1.5f,
                )
            }
        }
    }
}

private data class SleepBlock(
    val startX: Float,
    val width: Float,
)

private data class TimeLabel(
    val timeString: String,
    val percentage: Float,
)

private fun generateSleepBlocks(
    awakeMinutes: Int,
    remMinutes: Int,
    lightMinutes: Int,
    deepMinutes: Int,
    totalMinutes: Int,
    chartWidth: Float,
): Map<SleepStage, List<SleepBlock>> {
    if (totalMinutes == 0 || chartWidth <= 0) return emptyMap()

    val stages = listOf(
        SleepStage.AWAKE to awakeMinutes,
        SleepStage.REM to remMinutes,
        SleepStage.LIGHT to lightMinutes,
        SleepStage.DEEP to deepMinutes,
    )

    val blocks = mutableMapOf<SleepStage, List<SleepBlock>>()

    stages.forEach { (stage, minutes) ->
        if (minutes > 0) {
            val blockWidth = (minutes.toFloat() / totalMinutes) * chartWidth
            val stageBlocks = mutableListOf<SleepBlock>()

            var currentX = 0f
            val minBlockWidth = 4.dp.toPx()
            val blockCount = (blockWidth / minBlockWidth).toInt().coerceAtLeast(1)

            repeat(blockCount) { index ->
                val remainingWidth = blockWidth - (index * (blockWidth / blockCount))
                val width = (blockWidth / blockCount).coerceAtLeast(2.dp.toPx())

                stageBlocks.add(
                    SleepBlock(
                        startX = currentX,
                        width = width,
                    ),
                )
                currentX += width + 2.dp.toPx()
            }

            blocks[stage] = stageBlocks
        }
    }

    return blocks
}

private fun generateTimeLabels(
    startTimeMs: Long,
    endTimeMs: Long,
): List<TimeLabel> {
    val startInstant = Instant.ofEpochMilli(startTimeMs)
    val endInstant = Instant.ofEpochMilli(endTimeMs)

    val zoneId = ZoneId.systemDefault()
    val timeFormatter = DateTimeFormatter.ofPattern("h:mm a").withZone(zoneId)

    val durationMinutes = (endTimeMs - startTimeMs) / (1000 * 60)
    val labels = mutableListOf<TimeLabel>()

    labels.add(
        TimeLabel(
            timeString = timeFormatter.format(startInstant),
            percentage = 0f,
        ),
    )

    if (durationMinutes > 120) {
        val midTimeMs = startTimeMs + (durationMinutes / 2 * 60 * 1000)
        labels.add(
            TimeLabel(
                timeString = timeFormatter.format(Instant.ofEpochMilli(midTimeMs)),
                percentage = 50f,
            ),
        )
    }

    labels.add(
        TimeLabel(
            timeString = timeFormatter.format(endInstant),
            percentage = 100f,
        ),
    )

    return labels
}
