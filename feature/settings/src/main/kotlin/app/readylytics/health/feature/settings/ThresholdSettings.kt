package app.readylytics.health.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.readylytics.health.feature.settings.R
import app.readylytics.health.core.ui.components.MetricTooltip
import app.readylytics.health.feature.settings.common.SettingsConstants
import kotlin.math.roundToInt

@Composable
fun ThresholdSettingsSection(
    uiState: ThresholdSettingsState,
    onEvent: (SettingsEvent) -> Unit,
) {
    Column {
        var hrvOptimal by remember(uiState.hrvOptimalThreshold) { mutableFloatStateOf(uiState.hrvOptimalThreshold) }
        ThresholdSliderItem(
            label = stringResource(R.string.threshold_hrv_optimal_label),
            value = hrvOptimal,
            onValueChange = { hrvOptimal = it },
            onValueChangeFinished = { onEvent(SettingsEvent.HrvOptimalThresholdChanged(hrvOptimal)) },
            valueRange = 1.0f..1.2f,
            description = stringResource(R.string.threshold_hrv_optimal_desc),
        )

        var hrvWarning by remember(uiState.hrvWarningThreshold) { mutableFloatStateOf(uiState.hrvWarningThreshold) }
        ThresholdSliderItem(
            label = stringResource(R.string.threshold_hrv_warning_label),
            value = hrvWarning,
            onValueChange = { hrvWarning = it },
            onValueChangeFinished = { onEvent(SettingsEvent.HrvWarningThresholdChanged(hrvWarning)) },
            valueRange = 0.8f..1.0f,
            description = stringResource(R.string.threshold_hrv_warning_desc),
        )
        Spacer(modifier = Modifier.height(SettingsConstants.VERTICAL_SPACER))

        var rhrOptimal by remember(uiState.rhrOptimalThreshold) { mutableFloatStateOf(uiState.rhrOptimalThreshold) }
        ThresholdSliderItem(
            label = stringResource(R.string.threshold_rhr_optimal_label),
            value = rhrOptimal,
            onValueChange = { rhrOptimal = it },
            onValueChangeFinished = { onEvent(SettingsEvent.RhrOptimalThresholdChanged(rhrOptimal)) },
            valueRange = 0.8f..1.0f,
            description = stringResource(R.string.threshold_rhr_optimal_desc),
        )

        var rhrWarning by remember(uiState.rhrWarningThreshold) { mutableFloatStateOf(uiState.rhrWarningThreshold) }
        ThresholdSliderItem(
            label = stringResource(R.string.threshold_rhr_warning_label),
            value = rhrWarning,
            onValueChange = { rhrWarning = it },
            onValueChangeFinished = { onEvent(SettingsEvent.RhrWarningThresholdChanged(rhrWarning)) },
            valueRange = 1.0f..1.2f,
            description = stringResource(R.string.threshold_rhr_warning_desc),
        )

        // The circadian-consistency deviation threshold is no longer a flat slider here; it is
        // derived from the physiology profile (+ optional override) in the dedicated
        // "Circadian consistency" section (CircadianThresholdSettingsSection).
        var evaluationPeriod by remember(uiState.consistencyEvaluationDays) {
            mutableFloatStateOf(uiState.consistencyEvaluationDays.toFloat())
        }
        ThresholdSliderItem(
            label = stringResource(R.string.threshold_evaluation_period_label),
            value = evaluationPeriod,
            onValueChange = { evaluationPeriod = it },
            onValueChangeFinished = {
                onEvent(
                    SettingsEvent.ConsistencyEvaluationDaysChanged(evaluationPeriod.toInt()),
                )
            },
            valueRange = 3f..14f,
            steps = 10,
            displayValue = "${evaluationPeriod.toInt()} days",
            description = stringResource(R.string.threshold_evaluation_period_desc),
        )

        var baselineWindow by remember(uiState.consistencyBaselineDays) {
            mutableFloatStateOf(uiState.consistencyBaselineDays.toFloat())
        }
        ThresholdSliderItem(
            label = stringResource(R.string.threshold_baseline_window_label),
            value = baselineWindow,
            onValueChange = { baselineWindow = it },
            onValueChangeFinished = { onEvent(SettingsEvent.ConsistencyBaselineDaysChanged(baselineWindow.toInt())) },
            valueRange = 3f..30f,
            steps = 26,
            displayValue = "${baselineWindow.toInt()} sessions",
            description = stringResource(R.string.threshold_baseline_window_desc),
        )
    }
}

@Composable
fun ActivitySettingsSection(
    stepGoal: Int,
    onEvent: (SettingsEvent) -> Unit,
) {
    var currentStepGoal by remember(stepGoal) { mutableFloatStateOf(stepGoal.toFloat()) }

    Column(
        modifier =
            Modifier.padding(
                horizontal = SettingsConstants.HORIZONTAL_PADDING,
                vertical = SettingsConstants.VERTICAL_SPACER_SMALL,
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.label_daily_step_goal), style = MaterialTheme.typography.bodyMedium)
            MetricTooltip(description = stringResource(R.string.settings_step_goal_tooltip))
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "${currentStepGoal.roundToInt()} steps",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = currentStepGoal,
            onValueChange = { currentStepGoal = it },
            onValueChangeFinished = { onEvent(SettingsEvent.StepGoalChanged(currentStepGoal.roundToInt())) },
            valueRange = 1000f..30000f,
            steps = 57,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun SleepSettingsSection(
    uiState: SleepSettingsState,
    onEvent: (SettingsEvent) -> Unit,
) {
    var sleepGoalValue by remember(uiState.goalSleepHours) {
        mutableFloatStateOf(uiState.goalSleepHours)
    }

    Column {
        Column(
            modifier =
                Modifier.padding(
                    horizontal = SettingsConstants.HORIZONTAL_PADDING,
                    vertical = SettingsConstants.VERTICAL_SPACER_SMALL,
                ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.label_sleep_goal), style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = sleepGoalValue.toSleepHoursText(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Slider(
                value = sleepGoalValue,
                onValueChange = { sleepGoalValue = it },
                onValueChangeFinished = {
                    onEvent(SettingsEvent.GoalSleepHoursChanged(sleepGoalValue))
                },
                valueRange = 4f..12f,
                steps = 15,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun ThresholdSliderItem(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    description: String,
    steps: Int = ((valueRange.endInclusive - valueRange.start) * 100).roundToInt() - 1,
    displayValue: String = "${(value * 100).roundToInt()}%",
    onValueChangeFinished: (() -> Unit)? = null,
    onReset: (() -> Unit)? = null,
) {
    Column(
        modifier =
            Modifier.padding(
                horizontal = SettingsConstants.HORIZONTAL_PADDING,
                vertical = SettingsConstants.VERTICAL_SPACER_SMALL,
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            MetricTooltip(description = description)
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = displayValue,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (onReset != null) {
                IconButton(
                    onClick = onReset,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.action_reset_to_default),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

fun Float.toSleepHoursText(): String {
    val totalMinutes = (this * 60).roundToInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (minutes == 0) "${hours}h" else "${hours}h ${minutes}m"
}


