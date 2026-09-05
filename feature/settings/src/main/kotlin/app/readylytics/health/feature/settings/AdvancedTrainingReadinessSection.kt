package app.readylytics.health.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.designsystem.dimens
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import kotlin.math.roundToInt

/**
 * M3 `Slider.steps` counts the stops *between* the endpoints, so the interval is
 * `(max - min) / (steps + 1)`. 19 gives 20 five-unit stops across 75-175 and 20 one-percent stops
 * across 0.80-1.00, landing the documented defaults (100, 0.90) exactly on a stop.
 */
internal const val TRAINING_READINESS_SCALE_SLIDER_STEPS = 19
internal const val TRAINING_READINESS_WEIGHT_SLIDER_STEPS = 19

private const val WEIGHT_PERCENT_MULTIPLIER = 100

@Composable
fun TrainingReadinessSubsection(
    uiState: UIState,
    controlsEnabled: Boolean,
    isResyncing: Boolean,
    onUIEvent: (SettingsEvent) -> Unit,
) {
    Column {
        Text(
            text = stringResource(R.string.advanced_training_readiness_title),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium),
        )
        TrainingReadinessControls(
            uiState = uiState,
            controlsEnabled = controlsEnabled,
            isResyncing = isResyncing,
            onUIEvent = onUIEvent,
        )
    }
}

@Composable
private fun TrainingReadinessControls(
    uiState: UIState,
    controlsEnabled: Boolean,
    isResyncing: Boolean,
    onUIEvent: (SettingsEvent) -> Unit,
) {
    TrainingReadinessScaleSlider(
        scale = uiState.trainingReadinessResidualFatigueScale,
        controlsEnabled = controlsEnabled,
        onUIEvent = onUIEvent,
    )
    TrainingReadinessWeightSlider(
        weight = uiState.trainingReadinessLoadBalanceWeight,
        controlsEnabled = controlsEnabled,
        onUIEvent = onUIEvent,
    )
    TrainingReadinessResetAndApplyRow(
        hasPendingRecalc = uiState.hasPendingTrainingReadinessRecalc,
        controlsEnabled = controlsEnabled,
        isResyncing = isResyncing,
        onUIEvent = onUIEvent,
    )
}

@Composable
private fun TrainingReadinessScaleSlider(
    scale: Float,
    controlsEnabled: Boolean,
    onUIEvent: (SettingsEvent) -> Unit,
) {
    val scaleRange =
        SettingsDefaults.MIN_TRAINING_READINESS_RESIDUAL_FATIGUE_SCALE
            .rangeTo(SettingsDefaults.MAX_TRAINING_READINESS_RESIDUAL_FATIGUE_SCALE)
    var currentScale by remember(scale) { mutableFloatStateOf(scale) }
    ThresholdSliderItem(
        label = stringResource(R.string.advanced_training_readiness_scale_label),
        enabled = controlsEnabled,
        value = currentScale,
        onValueChange = { currentScale = it },
        onValueChangeFinished = {
            onUIEvent(SettingsEvent.TrainingReadinessScaleChanged(currentScale))
        },
        valueRange = scaleRange,
        steps = TRAINING_READINESS_SCALE_SLIDER_STEPS,
        displayValue = stringResource(R.string.advanced_training_readiness_scale_value, currentScale),
        description = stringResource(R.string.advanced_training_readiness_scale_desc),
    )
}

@Composable
private fun TrainingReadinessWeightSlider(
    weight: Float,
    controlsEnabled: Boolean,
    onUIEvent: (SettingsEvent) -> Unit,
) {
    val weightRange =
        SettingsDefaults.MIN_TRAINING_READINESS_LOAD_BALANCE_WEIGHT
            .rangeTo(SettingsDefaults.MAX_TRAINING_READINESS_LOAD_BALANCE_WEIGHT)
    var currentWeight by remember(weight) { mutableFloatStateOf(weight) }
    ThresholdSliderItem(
        label = stringResource(R.string.advanced_training_readiness_weight_label),
        enabled = controlsEnabled,
        value = currentWeight,
        onValueChange = { currentWeight = it },
        onValueChangeFinished = {
            onUIEvent(SettingsEvent.TrainingReadinessLoadBalanceWeightChanged(currentWeight))
        },
        valueRange = weightRange,
        steps = TRAINING_READINESS_WEIGHT_SLIDER_STEPS,
        displayValue =
            stringResource(
                R.string.advanced_training_readiness_weight_value,
                (currentWeight * WEIGHT_PERCENT_MULTIPLIER).roundToInt(),
            ),
        description = stringResource(R.string.advanced_training_readiness_weight_desc),
    )
}

@Composable
private fun TrainingReadinessResetAndApplyRow(
    hasPendingRecalc: Boolean,
    controlsEnabled: Boolean,
    isResyncing: Boolean,
    onUIEvent: (SettingsEvent) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = MaterialTheme.spacing.medium,
                    vertical = MaterialTheme.spacing.smallMedium,
                ),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smallMedium),
    ) {
        OutlinedButton(
            onClick = { onUIEvent(SettingsEvent.ResetTrainingReadinessToDefaults) },
            enabled = controlsEnabled,
            modifier = Modifier.weight(1f),
        ) {
            Text(stringResource(R.string.advanced_training_readiness_reset_button))
        }
        Button(
            onClick = { onUIEvent(SettingsEvent.RecalculateTrainingReadiness) },
            enabled = hasPendingRecalc && controlsEnabled,
            modifier = Modifier.weight(1f),
        ) {
            if (isResyncing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(MaterialTheme.dimens.iconMedium),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(modifier = Modifier.width(MaterialTheme.spacing.small))
            }
            Text(stringResource(R.string.advanced_training_readiness_apply_button))
        }
    }
}
