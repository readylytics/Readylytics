package com.gregor.lauritz.healthdashboard.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.gregor.lauritz.healthdashboard.domain.scoring.CircadianConsistencyResult
import com.gregor.lauritz.healthdashboard.domain.scoring.toStatus
import com.gregor.lauritz.healthdashboard.domain.scoring.toTimeString
import com.gregor.lauritz.healthdashboard.domain.util.roundToPercentInt

@Composable
fun CircadianConsistencyCard(
    result: CircadianConsistencyResult,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val scoreText =
        when (result) {
            is CircadianConsistencyResult.Calibrating -> "Calibrating"
            is CircadianConsistencyResult.MissingData -> "—"
            is CircadianConsistencyResult.Ready -> "${result.score.roundToPercentInt()}%"
        }
    val windowText =
        when (result) {
            is CircadianConsistencyResult.Calibrating,
            is CircadianConsistencyResult.MissingData,
            -> null
            is CircadianConsistencyResult.Ready ->
                "Median: ${result.medianBedtimeMinutes.toTimeString()}→${result.medianWakeMinutes.toTimeString()}"
        }

    val tooltipText =
        remember(result) {
            val thresholdMinutes =
                when (result) {
                    is CircadianConsistencyResult.Calibrating,
                    is CircadianConsistencyResult.MissingData,
                    -> 30
                    is CircadianConsistencyResult.Ready -> result.thresholdMinutes
                }
            circadianTooltipText(thresholdMinutes)
        }

    MetricCard(
        title = "Circadian Consistency",
        value = scoreText,
        secondaryText = windowText,
        status = result.toStatus(),
        tooltip = tooltipText,
        onClick = onClick,
        modifier = modifier,
    )
}
