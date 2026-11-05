package app.readylytics.health.feature.sleep

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.common.DateFormatUtils
import app.readylytics.health.core.ui.common.PeriodAverageSummary
import app.readylytics.health.core.ui.common.periodLabelFor
import app.readylytics.health.core.ui.common.rememberPeriodOrdinalLabel
import kotlin.math.abs
import kotlin.math.roundToInt
import app.readylytics.health.core.ui.R as CoreUiR

@Composable
internal fun SleepTrendSummaryRow(
    summary: PeriodAverageSummary,
    modifier: Modifier = Modifier,
) {
    val avg = summary.average ?: return
    val prev = summary.previousAverage
    val avgMinutes = (avg * 60f).roundToInt()
    val durationText = DateFormatUtils.formatSleepDuration(avgMinutes)

    val ordinalLabel = rememberPeriodOrdinalLabel(summary.granularity)
    val periodLabel = periodLabelFor(summary.granularity, summary.periodStartDate, ordinalLabel)
    val avgLabel = stringResource(CoreUiR.string.label_avg)

    val previousLabel = periodLabelFor(summary.granularity, summary.previousPeriodStartDate, ordinalLabel)
    val previousLabelText = stringResource(CoreUiR.string.period_summary_vs, previousLabel)

    val deltaMinutes = prev?.let { ((avg - it) * 60f).roundToInt() }
    val deltaText =
        if (deltaMinutes != null && deltaMinutes != 0) {
            val sign = if (deltaMinutes > 0) "+" else ""
            val absMin = abs(deltaMinutes)
            stringResource(R.string.sleep_trend_avg_delta_minutes_format, sign, absMin)
        } else {
            stringResource(R.string.sleep_trend_avg_delta_no_change)
        }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
    ) {
        Text(
            text = "$periodLabel $avgLabel: $durationText",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = deltaText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = previousLabelText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
