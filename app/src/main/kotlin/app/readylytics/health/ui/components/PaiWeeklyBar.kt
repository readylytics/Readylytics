package app.readylytics.health.ui.components

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.domain.util.roundToPercentInt

// 100 PAI fills 75% of the bar width
private const val BAR_MAX = 100f / 0.75f

@Composable
fun PaiWeeklyBar(
    dailyBreakdown: List<Pair<String, Float>>,
    totalPai: Float,
    modifier: Modifier = Modifier,
) {
    val status =
        when {
            totalPai >= 100f -> MetricStatus.OPTIMAL
            totalPai >= 75f -> MetricStatus.NEUTRAL
            totalPai >= 50f -> MetricStatus.WARNING
            else -> MetricStatus.POOR
        }
    val fillColor = status.gaugeColor()
    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val primaryColor = MaterialTheme.colorScheme.primary

    if (dailyBreakdown.isEmpty()) return

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

            val clipPath =
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

            clipPath(clipPath) {
                drawRect(color = trackColor, topLeft = Offset(0f, 0f), size = Size(totalWidth, barHeight))

                var xOffset = 0f
                for ((_, pai) in dailyBreakdown) {
                    if (pai > 0f) {
                        val segmentWidth = totalWidth * (pai / BAR_MAX)
                        drawRect(
                            color = fillColor,
                            topLeft = Offset(xOffset, 0f),
                            size = Size(segmentWidth, barHeight),
                        )
                        xOffset += segmentWidth
                    }
                }
            }

            // Outline drawn outside clip so the stroke sits on the edge
            drawRoundRect(
                color = outlineColor,
                cornerRadius = CornerRadius(radius),
                style = Stroke(width = 1.dp.toPx()),
            )
        }

        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                // Allow-listed: chart-widget label for the passed-in PAI series (bar geometry
                // uses the raw Float above); not a DailySummary metric read.
                text = "${totalPai.roundToPercentInt()} PAI",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = fillColor,
            )
            Text(
                text = status.name,
                style = MaterialTheme.typography.labelSmall,
                color = fillColor,
            )
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            dailyBreakdown.forEach { (label, pai) ->
                PaiDayLegendItem(
                    color = if (pai > 0f) fillColor else onSurfaceVariant.copy(alpha = 0.4f),
                    label = label,
                    pai = pai,
                    onSurfaceVariant = onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PaiDayLegendItem(
    color: Color,
    label: String,
    pai: Float,
    onSurfaceVariant: Color,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(modifier = Modifier.size(8.dp)) {
            drawCircle(color = color)
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = onSurfaceVariant,
        )
        Text(
            // Allow-listed: chart-widget per-day legend label for the passed-in PAI series.
            text = if (pai > 0f) pai.roundToPercentInt().toString() else "-",
            style = MaterialTheme.typography.labelSmall,
            color = onSurfaceVariant,
        )
    }
}
