package app.readylytics.health.feature.workouts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.LocalStatusColors
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.feature.workouts.R

/**
 * Horizontal legend row shown **below** the ACWR chart.
 *
 * Displays two legend items:
 * - A small filled rectangle representing the **Daily TRIMP** bar series.
 * - A thin horizontal line representing the **Strain Ratio** line series.
 *
 * Icon shapes are intentionally distinct so users can differentiate bar vs. line at a glance.
 * Styling follows the [app.readylytics.health.ui.components.BaselineLegend] pattern.
 *
 * @param trimpColor  Colour of the TRIMP bar series (typically `MaterialTheme.colorScheme.outline`).
 * @param ratioColor  Colour of the Strain Ratio line series (typically `MaterialTheme.colorScheme.primary`).
 */
@Composable
fun AcwrChartLegends(
    trimpColor: Color,
    ratioColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ── Bar legend ──────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier =
                    Modifier
                        .size(width = 12.dp, height = 8.dp)
                        .background(
                            color = trimpColor,
                            shape = MaterialTheme.shapes.extraSmall,
                        ),
            )
            Spacer(Modifier.width(MaterialTheme.spacing.extraSmallMedium))
            Text(
                text = stringResource(R.string.acwr_legend_daily_trimp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ── Line legend ─────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier =
                    Modifier
                        .size(width = 16.dp, height = 2.dp)
                        .background(ratioColor),
            )
            Spacer(Modifier.width(MaterialTheme.spacing.extraSmallMedium))
            Text(
                text = stringResource(R.string.acwr_legend_strain_ratio),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal data class TsbZoneVisual(
    val labelRes: Int,
    val legendColor: Color,
    val bandColor: Color,
)

@Composable
internal fun rememberTsbZoneVisuals(): List<TsbZoneVisual> {
    val statusColors = LocalStatusColors.current
    val tertiary = MaterialTheme.colorScheme.tertiary
    return remember(statusColors, tertiary) {
        listOf(
            TsbZoneVisual(
                labelRes = R.string.tsb_zone_very_fresh,
                legendColor = statusColors.neutral,
                bandColor = statusColors.neutral.copy(alpha = 0.16f),
            ),
            TsbZoneVisual(
                labelRes = R.string.tsb_zone_fresh,
                legendColor = tertiary,
                bandColor = tertiary.copy(alpha = 0.22f),
            ),
            TsbZoneVisual(
                labelRes = R.string.tsb_zone_optimal,
                legendColor = statusColors.optimal,
                bandColor = statusColors.optimal.copy(alpha = 0.20f),
            ),
            TsbZoneVisual(
                labelRes = R.string.tsb_zone_fatigued,
                legendColor = statusColors.warning,
                bandColor = statusColors.warning.copy(alpha = 0.20f),
            ),
            TsbZoneVisual(
                labelRes = R.string.tsb_zone_overreached,
                legendColor = statusColors.poor,
                bandColor = statusColors.poor.copy(alpha = 0.20f),
            ),
        )
    }
}

/**
 * Zone-label legend shown below the TSB chart, mirroring [AcwrChartLegends]'s layout but as a
 * vertical list since five zone labels don't comfortably fit a single horizontal row.
 *
 * Colours mirror [TsbChart]'s background zone band colors exactly.
 */
@Composable
fun TsbChartLegend(modifier: Modifier = Modifier) {
    val zoneVisuals = rememberTsbZoneVisuals()
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
    ) {
        zoneVisuals.forEach { visual ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier =
                        Modifier
                            .size(width = 12.dp, height = 8.dp)
                            .background(color = visual.legendColor, shape = MaterialTheme.shapes.extraSmall),
                )
                Spacer(Modifier.width(MaterialTheme.spacing.extraSmallMedium))
                Text(
                    text = stringResource(visual.labelRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
