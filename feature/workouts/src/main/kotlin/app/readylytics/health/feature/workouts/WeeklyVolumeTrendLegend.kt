package app.readylytics.health.feature.workouts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.spacing

private const val LEGEND_SWATCH_WIDTH_DP = 16
private const val LEGEND_SWATCH_HEIGHT_DP = 2

/** Legend for the "Training time comparison" cumulative volume chart. */
@Composable
internal fun WeeklyVolumeTrendLegend(modifier: Modifier = Modifier) {
    val primaryColor = MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LegendSwatch(color = primaryColor, dashed = false)
            Spacer(Modifier.width(MaterialTheme.spacing.extraSmallMedium))
            Text(
                text = stringResource(R.string.weekly_volume_trend_legend_this_week),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            LegendSwatch(color = primaryColor.copy(alpha = PREVIOUS_WEEK_LINE_ALPHA), dashed = true)
            Spacer(Modifier.width(MaterialTheme.spacing.extraSmallMedium))
            Text(
                text = stringResource(R.string.weekly_volume_trend_legend_last_week),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Draws a legend swatch matching the stroke of the line it labels: solid for the current week,
 * dashed with the same dash/gap lengths the previous-week line is drawn with, so the dashed
 * encoding is not lost in the legend.
 */
@Composable
private fun LegendSwatch(
    color: Color,
    dashed: Boolean,
    modifier: Modifier = Modifier,
) {
    val swatchModifier = modifier.size(width = LEGEND_SWATCH_WIDTH_DP.dp, height = LEGEND_SWATCH_HEIGHT_DP.dp)
    if (!dashed) {
        Box(modifier = swatchModifier.background(color))
        return
    }
    val density = LocalDensity.current
    val dashEffect =
        remember(density) {
            with(density) {
                PathEffect.dashPathEffect(
                    floatArrayOf(PREVIOUS_WEEK_DASH_LENGTH_DP.dp.toPx(), PREVIOUS_WEEK_GAP_LENGTH_DP.dp.toPx()),
                )
            }
        }
    Canvas(modifier = swatchModifier) {
        drawLine(
            color = color,
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = size.height,
            pathEffect = dashEffect,
        )
    }
}
