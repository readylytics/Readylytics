package app.readylytics.health.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import app.readylytics.health.core.designsystem.dimens
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.model.domain.scoring.SleepScoreWeightProfile
import app.readylytics.health.core.ui.components.MetricTooltip
import app.readylytics.health.feature.settings.R
import kotlin.math.roundToInt

@Composable
fun ActivitySettingsSection(
    stepGoal: Int,
    onEvent: (SettingsEvent) -> Unit,
) {
    var currentStepGoal by remember(stepGoal) { mutableFloatStateOf(stepGoal.toFloat()) }

    Column(
        modifier =
            Modifier.padding(
                horizontal = MaterialTheme.spacing.medium,
                vertical = MaterialTheme.spacing.extraSmall,
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
fun ThresholdSliderItem(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    description: String? = null,
    steps: Int = ((valueRange.endInclusive - valueRange.start) * 100).roundToInt() - 1,
    displayValue: String = "${(value * 100).roundToInt()}%",
    onValueChangeFinished: (() -> Unit)? = null,
    onReset: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    Column(
        modifier =
            Modifier.padding(
                horizontal = MaterialTheme.spacing.medium,
                vertical = MaterialTheme.spacing.extraSmall,
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (description != null) {
                MetricTooltip(description = description)
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = displayValue,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (onReset != null) {
                IconButton(
                    onClick = onReset,
                    enabled = enabled,
                    modifier = Modifier.size(MaterialTheme.dimens.iconContainerLarge),
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
            enabled = enabled,
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

internal fun steppedSliderSteps(
    min: Int,
    max: Int,
    step: Int,
): Int = ((max - min) / step) - 1

internal fun SleepScoreWeightProfile.labelRes(): Int =
    when (this) {
        SleepScoreWeightProfile.BALANCED -> R.string.settings_sleep_profile_balanced
        SleepScoreWeightProfile.DURATION_FOCUSED -> R.string.settings_sleep_profile_duration_focused
        SleepScoreWeightProfile.RECOVERY_FOCUSED -> R.string.settings_sleep_profile_recovery_focused
        SleepScoreWeightProfile.ARCHITECTURE_FOCUSED -> R.string.settings_sleep_profile_architecture_focused
        SleepScoreWeightProfile.CONTINUITY_FOCUSED -> R.string.settings_sleep_profile_continuity_focused
    }

internal fun SleepScoreWeightProfile.descriptionRes(): Int =
    when (this) {
        SleepScoreWeightProfile.BALANCED -> R.string.settings_sleep_profile_balanced_description
        SleepScoreWeightProfile.DURATION_FOCUSED -> R.string.settings_sleep_profile_duration_focused_description
        SleepScoreWeightProfile.RECOVERY_FOCUSED -> R.string.settings_sleep_profile_recovery_focused_description
        SleepScoreWeightProfile.ARCHITECTURE_FOCUSED -> R.string.settings_sleep_profile_architecture_focused_description
        SleepScoreWeightProfile.CONTINUITY_FOCUSED -> R.string.settings_sleep_profile_continuity_focused_description
    }
