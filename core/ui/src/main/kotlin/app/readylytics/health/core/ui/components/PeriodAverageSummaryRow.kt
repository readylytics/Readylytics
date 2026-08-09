package app.readylytics.health.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.R
import app.readylytics.health.core.ui.common.PeriodAverageSummary
import app.readylytics.health.core.ui.common.formatRoundedScoreDelta
import app.readylytics.health.core.ui.common.resolveOrNull
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Compact latest-bucket-vs-prior-bucket summary rendered beneath a bucketed trend chart,
 * e.g. "Aug Avg: 44 ms ↑3 vs Jul". Reuses the existing delta arrow/number formatting.
 */
@Composable
fun PeriodAverageSummaryRow(
    summary: PeriodAverageSummary,
    unit: String,
    decimalPlaces: Int,
    modifier: Modifier = Modifier,
) {
    val valueText =
        summary.average?.let { "${formatPeriodValue(it, decimalPlaces)} $unit" }
            ?: return
    val deltaText =
        formatRoundedScoreDelta(
            currentRounded = summary.average?.roundToInt(),
            previousRounded = summary.previousAverage?.roundToInt(),
        ).resolveOrNull() ?: return
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
            color = MaterialTheme.colorScheme.primary,
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
