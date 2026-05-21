package com.gregor.lauritz.healthdashboard.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity

private data class StageSegment(
    val label: String,
    val minutes: Int,
    val color: @Composable () -> Color,
)

@Composable
fun SleepArchitectureBar(
    session: SleepSessionEntity?,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val deepColor = colorScheme.primary
    val remColor = colorScheme.tertiary
    val lightSleep = colorScheme.outline
    val awakeColor = colorScheme.error
    val surfaceVariant = colorScheme.surfaceVariant
    val outlineColor = colorScheme.outlineVariant
    val onSurfaceVariant = colorScheme.onSurfaceVariant

    val totalMinutes =
        session?.let {
            it.deepSleepMinutes + it.remSleepMinutes + it.lightSleepMinutes + it.awakeMinutes
        } ?: 0

    if (session == null || totalMinutes == 0) {
        CalibrationBar(modifier = modifier, color = surfaceVariant, onSurfaceVariant = onSurfaceVariant)
        return
    }

    val segments =
        listOf(
            StageSegment("Deep", session.deepSleepMinutes) { deepColor },
            StageSegment("REM", session.remSleepMinutes) { remColor },
            StageSegment("Light", session.lightSleepMinutes) { lightSleep },
            StageSegment("Awake", session.awakeMinutes) { awakeColor },
        )

    val primaryColor = MaterialTheme.colorScheme.primary
    val resolvedColors = segments.map { it.color() }

    Column(modifier = modifier) {
        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(28.dp),
        ) {
            val totalWidth = size.width
            val barHeight = size.height
            val radius = barHeight / 2f

            val clipRoundRect =
                Path().apply {
                    addRoundRect(
                        RoundRect(
                            left = 0f,
                            top = 0f,
                            right = totalWidth,
                            bottom = barHeight,
                            cornerRadius = CornerRadius(radius),
                        ),
                    )
                }

            clipPath(clipRoundRect) {
                var xOffset = 0f
                segments.forEachIndexed { index, segment ->
                    val fraction = segment.minutes.toFloat() / totalMinutes
                    val segmentWidth = totalWidth * fraction
                    drawRect(
                        color = resolvedColors[index],
                        topLeft = Offset(xOffset, 0f),
                        size = Size(segmentWidth, barHeight),
                    )
                    xOffset += segmentWidth
                }
            }

            drawRoundRect(
                color = outlineColor,
                cornerRadius = CornerRadius(radius),
                style = Stroke(width = 1.dp.toPx()),
            )
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            segments.forEachIndexed { index, segment ->
                StageLegendItem(
                    color = resolvedColors[index],
                    label = segment.label,
                    duration = formatMinutes(segment.minutes),
                    onSurfaceVariant = onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun CalibrationBar(
    modifier: Modifier = Modifier,
    color: Color,
    onSurfaceVariant: Color,
) {
    Column(modifier = modifier) {
        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(28.dp),
        ) {
            drawRoundRect(
                color = color,
                cornerRadius = CornerRadius(size.height / 2f),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "No data — calibrating",
            style = MaterialTheme.typography.labelSmall,
            color = onSurfaceVariant,
        )
    }
}

@Composable
private fun StageLegendItem(
    color: Color,
    label: String,
    duration: String,
    onSurfaceVariant: Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(8.dp)) {
            drawCircle(color = color)
        }
        Spacer(Modifier.width(4.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = onSurfaceVariant,
            )
            Text(
                text = duration,
                style = MaterialTheme.typography.labelSmall,
                color = onSurfaceVariant,
            )
        }
    }
}

private fun formatMinutes(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
