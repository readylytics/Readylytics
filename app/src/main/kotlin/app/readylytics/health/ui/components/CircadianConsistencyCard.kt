package app.readylytics.health.ui.components

import app.readylytics.health.core.ui.components.MetricCard

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.readylytics.health.R
import app.readylytics.health.domain.scoring.CircadianConsistencyResult
import app.readylytics.health.domain.scoring.toStatus
import app.readylytics.health.domain.scoring.toTimeString
import app.readylytics.health.domain.util.roundToPercentInt

@Composable
fun CircadianConsistencyCard(
    result: CircadianConsistencyResult,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val scoreText =
        when (result) {
            is CircadianConsistencyResult.Calibrating -> stringResource(R.string.spo2_calibrating)
            is CircadianConsistencyResult.MissingData -> "—"
            is CircadianConsistencyResult.Ready -> "${result.score.roundToPercentInt()}%"
        }
    val windowText =
        when (result) {
            is CircadianConsistencyResult.Calibrating,
            is CircadianConsistencyResult.MissingData,
            -> null
            is CircadianConsistencyResult.Ready ->
                stringResource(
                    R.string.label_circadian_median,
                    result.medianBedtimeMinutes.toTimeString(),
                    result.medianWakeMinutes.toTimeString(),
                )
        }

    val thresholdMinutes =
        when (result) {
            is CircadianConsistencyResult.Calibrating,
            is CircadianConsistencyResult.MissingData,
            -> 30
            is CircadianConsistencyResult.Ready -> result.thresholdMinutes
        }
    val tooltipText = stringResource(R.string.tooltip_circadian_score, thresholdMinutes)

    MetricCard(
        title = stringResource(R.string.label_circadian_consistency),
        value = scoreText,
        secondaryText = windowText,
        status = result.toStatus(),
        tooltip = tooltipText,
        onClick = onClick,
        modifier = modifier,
    )
}
