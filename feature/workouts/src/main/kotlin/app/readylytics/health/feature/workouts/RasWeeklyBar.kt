package app.readylytics.health.feature.workouts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.ui.components.HealthProgressBar
import app.readylytics.health.core.ui.components.ProgressBarSegment
import app.readylytics.health.core.ui.components.gaugeColor
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.domain.util.roundToPercentInt
import app.readylytics.health.feature.workouts.R

// 100 RAS fills 75% of the bar width
private const val BAR_MAX = 100f / 0.75f

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
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val primaryColor = MaterialTheme.colorScheme.primary

    if (dailyBreakdown.isEmpty()) return

    val chartSummary = stringResource(R.string.chart_accessibility_ras_summary)

    Column(modifier = modifier) {
        HealthProgressBar(
            segments = dailyBreakdown.map { ProgressBarSegment(value = it.second, color = fillColor) },
            max = BAR_MAX,
            height = 28.dp,
            trackColor = trackColor,
            outlineColor = outlineColor,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = chartSummary
                    },
        )

        Spacer(Modifier.height(8.dp))

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
        Spacer(Modifier.height(2.dp))
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
