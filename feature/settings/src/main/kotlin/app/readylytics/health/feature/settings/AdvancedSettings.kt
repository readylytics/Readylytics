package app.readylytics.health.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import app.readylytics.health.core.model.domain.validation.SettingsValidators
import app.readylytics.health.core.model.domain.validation.ValidationResult
import app.readylytics.health.core.model.domain.validation.ValidationRule
import app.readylytics.health.core.ui.components.MetricTooltip
import app.readylytics.health.feature.settings.common.resyncGateEnabled

@Composable
fun AdvancedSettingsSection(
    sleepState: SleepSettingsState,
    uiState: UIState,
    onEvent: (SettingsEvent) -> Unit,
    onPhysiologyEvent: (SettingsEvent) -> Unit,
    onUIEvent: (SettingsEvent) -> Unit,
    isResyncing: Boolean = false,
) {
    val controlsEnabled = resyncGateEnabled(isResyncing)

    Column {
        BaselineOverridesSubsection(
            sleepState = sleepState,
            controlsEnabled = controlsEnabled,
            onEvent = onEvent,
        )

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.smallMedium))

        RestingHrPercentileSubsection(
            sleepState = sleepState,
            controlsEnabled = controlsEnabled,
            onEvent = onEvent,
        )

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.smallMedium))

        RecoveryToleranceSubsection(
            hrrToleranceSeconds = uiState.hrrToleranceSeconds,
            controlsEnabled = controlsEnabled,
            onUIEvent = onUIEvent,
        )

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.smallMedium))

        RasScalingSubsection(
            rasScalingFactor = uiState.rasScalingFactor,
            controlsEnabled = controlsEnabled,
            onPhysiologyEvent = onPhysiologyEvent,
            onUIEvent = onUIEvent,
        )

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.smallMedium))

        TrainingLoadSubsection(
            uiState = uiState,
            controlsEnabled = controlsEnabled,
            isResyncing = isResyncing,
            onUIEvent = onUIEvent,
        )

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.smallMedium))

        ResidualFatigueSubsection(
            uiState = uiState,
            controlsEnabled = controlsEnabled,
            onUIEvent = onUIEvent,
        )
    }
}

@Composable
private fun BaselineOverridesSubsection(
    sleepState: SleepSettingsState,
    controlsEnabled: Boolean,
    onEvent: (SettingsEvent) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium)) {
        Text(
            stringResource(R.string.advanced_baseline_overrides_title),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(bottom = MaterialTheme.spacing.small),
        )
        BaselineOverrideField(
            initialValue = sleepState.hrvBaselineOverride?.toInt()?.toString() ?: "",
            label = stringResource(R.string.advanced_hrv_baseline_label),
            controlsEnabled = controlsEnabled,
            validator = SettingsValidators.HRV_BASELINE_RULE,
            onValidValue = { onEvent(SettingsEvent.HrvBaselineChanged(it)) },
            onCleared = { onEvent(SettingsEvent.HrvBaselineCleared) },
        )
        Spacer(modifier = Modifier.height(MaterialTheme.spacing.small))
        BaselineOverrideField(
            initialValue = sleepState.rhrBaselineOverride?.toInt()?.toString() ?: "",
            label = stringResource(R.string.advanced_rhr_baseline_label),
            controlsEnabled = controlsEnabled,
            validator = SettingsValidators.RHR_BASELINE_RULE,
            onValidValue = { onEvent(SettingsEvent.RhrBaselineChanged(it)) },
            onCleared = { onEvent(SettingsEvent.RhrBaselineCleared) },
        )
    }
}

@Composable
private fun BaselineOverrideField(
    initialValue: String,
    label: String,
    controlsEnabled: Boolean,
    validator: ValidationRule<String>,
    onValidValue: (String) -> Unit,
    onCleared: () -> Unit,
) {
    var text by remember(initialValue) { mutableStateOf(initialValue) }
    val validation = validator.validate(text)

    OutlinedTextField(
        value = text,
        enabled = controlsEnabled,
        onValueChange = { value ->
            text = value
            val result = validator.validate(value)
            if (result is ValidationResult.Valid) {
                value.toIntOrNull()?.let { onValidValue(it.toString()) }
            }
        },
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label)
                MetricTooltip(description = stringResource(R.string.advanced_baseline_override_tooltip))
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = text.isNotEmpty() && validation is ValidationResult.Invalid,
        supportingText = {
            if (validation is ValidationResult.Invalid) Text(validation.message)
        },
        trailingIcon = {
            if (text.isNotEmpty()) {
                IconButton(
                    onClick = {
                        text = ""
                        onCleared()
                    },
                    enabled = controlsEnabled,
                ) {
                    Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.accessibility_clear))
                }
            }
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun RestingHrPercentileSubsection(
    sleepState: SleepSettingsState,
    controlsEnabled: Boolean,
    onEvent: (SettingsEvent) -> Unit,
) {
    var percentileValue by remember(sleepState.restingHrPercentile) {
        mutableIntStateOf(sleepState.restingHrPercentile)
    }

    Column(modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.advanced_resting_hr_percentile_label))
            MetricTooltip(description = stringResource(R.string.advanced_resting_hr_percentile_tooltip))
        }
        Spacer(modifier = Modifier.height(MaterialTheme.spacing.small))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = percentileValue.toFloat(),
                enabled = controlsEnabled,
                onValueChange = { percentileValue = it.toInt() },
                onValueChangeFinished = {
                    val validation =
                        SettingsValidators.RESTING_HR_PERCENTILE_RULE.validate(percentileValue.toString())
                    if (validation is ValidationResult.Valid) {
                        onEvent(SettingsEvent.RestingHrPercentileChanged(percentileValue))
                    }
                },
                valueRange = 1f..15f,
                steps = 13,
                modifier = Modifier.weight(1f),
            )
            Text(
                "$percentileValue",
                modifier = Modifier.padding(start = MaterialTheme.spacing.medium),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun RecoveryToleranceSubsection(
    hrrToleranceSeconds: Int,
    controlsEnabled: Boolean,
    onUIEvent: (SettingsEvent) -> Unit,
) {
    var hrrTolerance by remember(hrrToleranceSeconds) {
        mutableFloatStateOf(hrrToleranceSeconds.toFloat())
    }
    val hrrToleranceRange =
        SettingsDefaults.MIN_HRR_TOLERANCE_SECONDS.toFloat()..SettingsDefaults.MAX_HRR_TOLERANCE_SECONDS.toFloat()
    ThresholdSliderItem(
        label = stringResource(R.string.advanced_hrr_tolerance_label),
        enabled = controlsEnabled,
        value = hrrTolerance,
        onValueChange = { hrrTolerance = it },
        onValueChangeFinished = {
            onUIEvent(SettingsEvent.HrrToleranceSecondsChanged(hrrTolerance.toInt()))
        },
        valueRange = hrrToleranceRange,
        steps = 8,
        displayValue = stringResource(R.string.advanced_hrr_tolerance_seconds, hrrTolerance.toInt()),
        description = stringResource(R.string.advanced_hrr_tolerance_tooltip),
    )
}

@Composable
private fun RasScalingSubsection(
    rasScalingFactor: Float,
    controlsEnabled: Boolean,
    onPhysiologyEvent: (SettingsEvent) -> Unit,
    onUIEvent: (SettingsEvent) -> Unit,
) {
    var rasScaling by remember(rasScalingFactor) { mutableFloatStateOf(rasScalingFactor) }
    ThresholdSliderItem(
        label = stringResource(R.string.advanced_ras_scaling_label),
        value = rasScaling,
        onValueChange = { rasScaling = it },
        onValueChangeFinished = { onUIEvent(SettingsEvent.RasScalingFactorChanged(rasScaling)) },
        onReset = { onPhysiologyEvent(SettingsEvent.ResetRasScalingFactor) },
        valueRange = 0.1f..0.3f,
        steps = 20,
        displayValue = "%.2f".format(rasScaling),
        description = stringResource(R.string.advanced_ras_scaling_tooltip),
        enabled = controlsEnabled,
    )
}
