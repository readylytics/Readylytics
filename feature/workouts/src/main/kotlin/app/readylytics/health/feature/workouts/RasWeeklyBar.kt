package app.readylytics.health.feature.workouts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.dimens
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.components.GOAL_FILL_CAP_FRACTION
import app.readylytics.health.core.ui.components.M3MetricBar
import app.readylytics.health.core.ui.components.containerColor
import app.readylytics.health.core.ui.components.gaugeColor
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.domain.util.roundToPercentInt
import app.readylytics.health.feature.workouts.R

// 100 RAS fills 75% of the bar width
private const val BAR_MAX = 100f / GOAL_FILL_CAP_FRACTION

@Composable
fun RasWeeklyBar(
    dailyBreakdown: List<Pair<String, Float>>,
    totalRas: Float,
    modifier: Modifier = Modifier,
) {
    val status =
        when {
            totalRas >= 100f -> MetricStatus.OPTIMAL
            totalRas >= 75f -> MetricStatus.NEUTRAL
            totalRas >= 50f -> MetricStatus.WARNING
            else -> MetricStatus.POOR
        }
    val fillColor = status.gaugeColor()
    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    if (dailyBreakdown.isEmpty()) return

    val chartSummary = stringResource(R.string.chart_accessibility_ras_summary)

    Column(modifier = modifier) {
        M3MetricBar(
            progressFraction = (totalRas / BAR_MAX).coerceIn(0f, 1f),
            activeColor = fillColor,
            trackColor = trackColor,
            barHeight = MaterialTheme.dimens.miniBarHeight,
            markerColor = status.containerColor(),
            showMarker = true,
            animateProgress = false,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = chartSummary },
        )

        Spacer(Modifier.height(MaterialTheme.spacing.small))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            dailyBreakdown.forEach { (label, ras) ->
                RasDayLegendItem(
                    color = if (ras > 0f) fillColor else onSurfaceVariant.copy(alpha = 0.4f),
                    label = label,
                    ras = ras,
                    onSurfaceVariant = onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RasDayLegendItem(
    color: Color,
    label: String,
    ras: Float,
    onSurfaceVariant: Color,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(modifier = Modifier.size(8.dp)) {
            drawCircle(color = color)
        }
        Spacer(Modifier.height(MaterialTheme.spacing.hairline))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = onSurfaceVariant,
        )
        Text(
            // Allow-listed: chart-widget per-day legend label for the passed-in RAS series.
            text = if (ras > 0f) ras.roundToPercentInt().toString() else "-",
            style = MaterialTheme.typography.labelSmall,
            color = onSurfaceVariant,
        )
    }
}

@Composable
fun RasWeeklyCard(
    dailyBreakdown: List<Pair<String, Float>>,
    totalRas: Int?,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.padding(MaterialTheme.spacing.medium)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = stringResource(R.string.workout_stats_ras_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    totalRas?.let { total ->
                        Text(
                            text = total.toString(),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    app.readylytics.health.core.ui.components.MetricTooltip(
                        description = stringResource(app.readylytics.health.core.ui.R.string.tooltip_ras),
                    )
                }
            }
            Spacer(Modifier.height(MaterialTheme.spacing.smallMedium))
            RasWeeklyBar(
                dailyBreakdown = dailyBreakdown,
                totalRas = totalRas?.toFloat() ?: 0f,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
