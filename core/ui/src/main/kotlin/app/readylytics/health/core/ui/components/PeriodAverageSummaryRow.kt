package app.readylytics.health.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.designsystem.LocalStatusColors
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.R
import app.readylytics.health.core.ui.common.DeltaDirection
import app.readylytics.health.core.ui.common.DeltaOutcome
import app.readylytics.health.core.ui.common.PeriodAverageSummary
import app.readylytics.health.core.ui.common.assessDeltaOutcome
import app.readylytics.health.core.ui.common.formatRoundedScoreDelta
import app.readylytics.health.core.ui.common.resolveOrNull
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Compact latest-bucket-vs-prior-bucket summary rendered beneath a bucketed trend chart,
 * e.g. "Aug Avg: 44 ms ↑3 vs Jul". Reuses the existing delta arrow/number formatting.
 * The delta arrow is colored by [direction]: favourable movement uses the optimal color, the
 * opposite the warning color, and neutral/no-change the neutral color.
 */
@Composable
fun PeriodAverageSummaryRow(
    summary: PeriodAverageSummary,
    unit: String,
    decimalPlaces: Int,
    modifier: Modifier = Modifier,
    direction: DeltaDirection = DeltaDirection.HIGHER_IS_BETTER,
) {
    val average = summary.average ?: return
    val valueText = "${formatPeriodValue(average, decimalPlaces)} $unit"
    val currentRounded = average.roundToInt()
    val previousRounded = summary.previousAverage?.roundToInt()
    val deltaText =
        formatRoundedScoreDelta(
            currentRounded = currentRounded,
            previousRounded = previousRounded,
        ).resolveOrNull() ?: return
    val statusColors = LocalStatusColors.current
    val deltaColor =
        when (
            assessDeltaOutcome(
                currentRounded = currentRounded,
                previousRounded = previousRounded,
                direction = direction,
            )
        ) {
            DeltaOutcome.IMPROVED -> statusColors.optimal
            DeltaOutcome.WORSENED -> statusColors.warning
            DeltaOutcome.NEUTRAL -> statusColors.neutral
            null -> statusColors.neutral
        }
    val avgLabel = stringResource(R.string.label_avg)
    val previousLabel = stringResource(R.string.period_summary_vs, summary.previousPeriodLabel)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
    ) {
        Text(
            text = "${summary.periodLabel} $avgLabel: $valueText",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = deltaText,
            style = MaterialTheme.typography.bodySmall,
            color = deltaColor,
        )
        Text(
            text = previousLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatPeriodValue(
    value: Float,
    decimalPlaces: Int,
): String =
    if (decimalPlaces == 0) {
        value.roundToInt().toString()
    } else {
        String.format(Locale.getDefault(), "%.${decimalPlaces}f", value)
    }
