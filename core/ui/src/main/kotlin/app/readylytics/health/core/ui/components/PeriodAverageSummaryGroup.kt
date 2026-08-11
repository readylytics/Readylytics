package app.readylytics.health.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.LocalStatusColors
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.R
import app.readylytics.health.core.ui.common.DeltaDirection
import app.readylytics.health.core.ui.common.DeltaOutcome
import app.readylytics.health.core.ui.common.PeriodAverageSummary
import app.readylytics.health.core.ui.common.assessDeltaOutcome
import app.readylytics.health.core.ui.common.formatRoundedScoreDelta
import app.readylytics.health.core.ui.common.periodLabelFor
import app.readylytics.health.core.ui.common.rememberPeriodOrdinalLabel
import app.readylytics.health.core.ui.common.resolveOrNull
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * One labeled series (e.g. "Systolic", tinted to match its chart legend swatch) feeding
 * into [PeriodAverageSummaryGroup].
 */
data class LabeledPeriodAverage(
    val label: String,
    val color: Color,
    val summary: PeriodAverageSummary,
)

/**
 * Two-metric variant of [PeriodAverageSummaryRow]: one shared period header
 * (e.g. "Aug Avg:") followed by one labeled, color-coded line per metric, and a single
 * trailing "vs Jul" caption. Renders nothing if either metric has no average yet.
 */
@Composable
fun PeriodAverageSummaryGroup(
    primary: LabeledPeriodAverage,
    secondary: LabeledPeriodAverage,
    unit: String,
    decimalPlaces: Int,
    modifier: Modifier = Modifier,
    direction: DeltaDirection = DeltaDirection.NEUTRAL,
) {
    val primaryAverage = primary.summary.average ?: return
    val secondaryAverage = secondary.summary.average ?: return

    val ordinalLabel = rememberPeriodOrdinalLabel(primary.summary.granularity)

    fun periodLabel(
        summary: PeriodAverageSummary,
        date: LocalDate,
    ) = periodLabelFor(summary.granularity, date, ordinalLabel)

    val periodLabel = periodLabel(primary.summary, primary.summary.periodStartDate)
    val previousLabel = periodLabel(primary.summary, primary.summary.previousPeriodStartDate)
    val avgLabel = stringResource(R.string.label_avg)
    val previousLabelText = stringResource(R.string.period_summary_vs, previousLabel)
    val statusColors = LocalStatusColors.current

    @Composable
    fun MetricRow(
        metric: LabeledPeriodAverage,
        average: Float,
    ) {
        val valueText =
            formatTrendTooltipValue(
                value = average,
                decimalPlaces = decimalPlaces,
                hideUnit = false,
                unit = unit,
            )
        val currentRounded = average.roundToInt()
        val previousRounded = metric.summary.previousAverage?.roundToInt()
        val deltaText =
            formatRoundedScoreDelta(
                currentRounded = currentRounded,
                previousRounded = previousRounded,
            ).resolveOrNull()
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
        ) {
            Box(modifier = Modifier.size(width = 12.dp, height = 2.dp).background(metric.color))
            Text(
                text = "${metric.label}: $valueText",
                style = MaterialTheme.typography.bodySmall,
                color = metric.color,
            )
            if (deltaText != null) {
                Text(
                    text = deltaText,
                    style = MaterialTheme.typography.bodySmall,
                    color = deltaColor,
                )
            }
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall)) {
        Text(
            text = "$periodLabel $avgLabel:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MetricRow(primary, primaryAverage)
        MetricRow(secondary, secondaryAverage)
        Text(
            text = previousLabelText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
