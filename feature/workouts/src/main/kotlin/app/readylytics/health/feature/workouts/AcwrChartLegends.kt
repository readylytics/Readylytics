package app.readylytics.health.feature.workouts

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
