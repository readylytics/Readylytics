package app.readylytics.health.feature.sleep

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.dimens
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.domain.repository.SleepSessionData
import app.readylytics.health.feature.sleep.R

private data class StageSegment(
    val label: String,
    val minutes: Int,
    val color: @Composable () -> Color,
)

@Composable
fun SleepArchitectureBar(
    session: SleepSessionData?,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val deepColor = colorScheme.primary
    val lightSleep = colorScheme.secondary
    val remColor = colorScheme.tertiary
    val awakeColor = colorScheme.error
    val defaultCardContainer = colorScheme.surfaceContainerHighest
    val outlineColor = colorScheme.outlineVariant
    val onSurfaceVariant = colorScheme.onSurfaceVariant

    val totalMinutes =
        session?.let {
            it.deepSleepMinutes + it.remSleepMinutes + it.lightSleepMinutes + it.awakeMinutes
        } ?: 0

    if (session == null || totalMinutes == 0) {
        CalibrationBar(modifier = modifier, color = defaultCardContainer, onSurfaceVariant = onSurfaceVariant)
        return
    }

    val deepLabel = stringResource(R.string.sleep_stage_deep)
    val remLabel = stringResource(R.string.sleep_stage_rem)
    val lightLabel = stringResource(R.string.sleep_stage_light)
    val awakeLabel = stringResource(R.string.sleep_stage_awake)

    val segments =
        listOf(
            StageSegment(deepLabel, session.deepSleepMinutes) { deepColor },
            StageSegment(remLabel, session.remSleepMinutes) { remColor },
            StageSegment(lightLabel, session.lightSleepMinutes) { lightSleep },
            StageSegment(awakeLabel, session.awakeMinutes) { awakeColor },
        )

    val primaryColor = MaterialTheme.colorScheme.primary
    val resolvedColors = segments.map { it.color() }

    val chartSummary = stringResource(R.string.chart_accessibility_sleep_architecture_summary)

    Column(modifier = modifier) {
        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(MaterialTheme.dimens.miniBarHeight)
                    .semantics {
                        contentDescription = chartSummary
                    },
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

        Spacer(Modifier.height(MaterialTheme.spacing.small))

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
                    .height(MaterialTheme.dimens.miniBarHeight),
        ) {
            drawRoundRect(
                color = color,
                cornerRadius = CornerRadius(size.height / 2f),
            )
        }
        Spacer(Modifier.height(MaterialTheme.spacing.small))
        Text(
            text = stringResource(R.string.message_sleep_no_data_calibrating),
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
        Spacer(Modifier.width(MaterialTheme.spacing.extraSmall))
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
