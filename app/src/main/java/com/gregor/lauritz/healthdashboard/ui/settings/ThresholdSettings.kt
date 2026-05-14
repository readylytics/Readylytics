package com.gregor.lauritz.healthdashboard.ui.settings

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
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.ui.components.MetricTooltip
import com.gregor.lauritz.healthdashboard.ui.settings.common.SettingsConstants
import kotlin.math.roundToInt

@Composable
fun ThresholdSettingsSection(
    uiState: ThresholdSettingsState,
    onEvent: (SettingsEvent) -> Unit,
) {
    Column {
        var hrvOptimal by remember(uiState.hrvOptimalThreshold) { mutableFloatStateOf(uiState.hrvOptimalThreshold) }
        ThresholdSliderItem(
            label = "HRV Optimal",
            value = hrvOptimal,
            onValueChange = { hrvOptimal = it },
            onValueChangeFinished = { onEvent(SettingsEvent.HrvOptimalThresholdChanged(hrvOptimal)) },
            valueRange = 1.0f..1.2f,
            description = "HRV ratio to baseline to be considered Optimal (e.g. 100-120%).",
        )

        var hrvWarning by remember(uiState.hrvWarningThreshold) { mutableFloatStateOf(uiState.hrvWarningThreshold) }
        ThresholdSliderItem(
            label = "HRV Warning",
            value = hrvWarning,
            onValueChange = { hrvWarning = it },
            onValueChangeFinished = { onEvent(SettingsEvent.HrvWarningThresholdChanged(hrvWarning)) },
            valueRange = 0.8f..1.0f,
            description = "HRV ratio to baseline to be considered Warning (e.g. 80-100%).",
        )
        Spacer(modifier = Modifier.height(SettingsConstants.VERTICAL_SPACER))

        var rhrOptimal by remember(uiState.rhrOptimalThreshold) { mutableFloatStateOf(uiState.rhrOptimalThreshold) }
        ThresholdSliderItem(
            label = "RHR Optimal",
            value = rhrOptimal,
            onValueChange = { rhrOptimal = it },
            onValueChangeFinished = { onEvent(SettingsEvent.RhrOptimalThresholdChanged(rhrOptimal)) },
            valueRange = 0.8f..1.0f,
            description = "RHR ratio to baseline to be considered Optimal (e.g. 80-100%).",
        )

        var rhrWarning by remember(uiState.rhrWarningThreshold) { mutableFloatStateOf(uiState.rhrWarningThreshold) }
        ThresholdSliderItem(
            label = "RHR Warning",
            value = rhrWarning,
            onValueChange = { rhrWarning = it },
            onValueChangeFinished = { onEvent(SettingsEvent.RhrWarningThresholdChanged(rhrWarning)) },
            valueRange = 1.0f..1.2f,
            description = "RHR ratio to baseline to be considered Warning (e.g. 100-120%).",
        )

        var consistencyWindow by remember(uiState.consistencyThresholdMinutes) {
            mutableFloatStateOf(uiState.consistencyThresholdMinutes.toFloat())
        }
        ThresholdSliderItem(
            label = "Consistency Window",
            value = consistencyWindow,
            onValueChange = { consistencyWindow = it },
            onValueChangeFinished = { onEvent(SettingsEvent.ConsistencyThresholdChanged(consistencyWindow.toInt())) },
            valueRange = 0f..90f,
            steps = 17,
            displayValue = "${consistencyWindow.toInt()} min",
            description =
                "±Grace period (in minutes) around your median bedtime and wake time before your score " +
                    "starts to drop. Default: 30 min.",
        )

        var evaluationPeriod by remember(uiState.consistencyEvaluationDays) {
            mutableFloatStateOf(uiState.consistencyEvaluationDays.toFloat())
        }
        ThresholdSliderItem(
            label = "Evaluation Period",
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
            description = "Number of recent sleep sessions scored to compute your current consistency. Default: 7.",
        )

        var baselineWindow by remember(uiState.consistencyBaselineDays) {
            mutableFloatStateOf(uiState.consistencyBaselineDays.toFloat())
        }
        ThresholdSliderItem(
            label = "Baseline Window",
            value = baselineWindow,
            onValueChange = { baselineWindow = it },
            onValueChangeFinished = { onEvent(SettingsEvent.ConsistencyBaselineDaysChanged(baselineWindow.toInt())) },
            valueRange = 3f..30f,
            steps = 26,
            displayValue = "${baselineWindow.toInt()} sessions",
            description = "Number of past sleep sessions used to calculate your median bedtime anchor. Default: 14.",
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
            Text("Daily Step Goal", style = MaterialTheme.typography.bodyMedium)
            MetricTooltip(description = "Target steps per day. Reaching this goal shows as Optimal on the dashboard.")
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
                Text("Sleep Goal", style = MaterialTheme.typography.bodyMedium)
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
                        contentDescription = "Reset to default",
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
