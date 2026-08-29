package app.readylytics.health.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.model.data.preferences.SettingsDefaults

/**
 * M3 `Slider.steps` counts the stops *between* the endpoints, so the interval is
 * `(max - min) / (steps + 1)`. 89 gives 90 one-hour stops across 6–96 h, which puts the documented
 * 24 h default exactly on a stop. The previous 90 produced 0.989 h intervals, so the default was
 * reachable only via Reset and the label rendered "23.9 h".
 */
internal const val RESIDUAL_FATIGUE_HALF_LIFE_SLIDER_STEPS = 89

/** 48 gives 49 stops of 0.1 across 0.1–5.0, putting the documented 1.00 default on a stop. */
internal const val RESIDUAL_FATIGUE_GAIN_SLIDER_STEPS = 48

@Composable
fun ResidualFatigueSubsection(
    uiState: UIState,
    controlsEnabled: Boolean,
    onUIEvent: (SettingsEvent) -> Unit,
) {
    Column {
        Text(
            text = stringResource(R.string.advanced_residual_fatigue_title),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium),
        )
        Spacer(modifier = Modifier.height(MaterialTheme.spacing.small))

        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            supportingContent = {
                Text(
                    text = stringResource(R.string.advanced_residual_fatigue_enabled_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingContent = {
                Switch(
                    checked = uiState.residualFatigueEnabled,
                    onCheckedChange = { onUIEvent(SettingsEvent.ResidualFatigueEnabledChanged(it)) },
                    enabled = controlsEnabled,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.advanced_residual_fatigue_enabled_label),
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        if (uiState.residualFatigueEnabled) {
            ResidualFatigueControls(
                uiState = uiState,
                controlsEnabled = controlsEnabled,
                onUIEvent = onUIEvent,
            )
        }
    }
}

@Composable
private fun ResidualFatigueControls(
    uiState: UIState,
    controlsEnabled: Boolean,
    onUIEvent: (SettingsEvent) -> Unit,
) {
    var halfLife by remember(uiState.residualFatigueHalfLifeHours) {
        mutableFloatStateOf(uiState.residualFatigueHalfLifeHours)
    }
    val minHalfLife = SettingsDefaults.MIN_RESIDUAL_FATIGUE_HALF_LIFE_HOURS
    val maxHalfLife = SettingsDefaults.MAX_RESIDUAL_FATIGUE_HALF_LIFE_HOURS
    ThresholdSliderItem(
        label = stringResource(R.string.advanced_residual_fatigue_half_life_label),
        enabled = controlsEnabled,
        value = halfLife,
        onValueChange = { halfLife = it },
        onValueChangeFinished = {
            onUIEvent(SettingsEvent.ResidualFatigueHalfLifeChanged(halfLife))
        },
        valueRange = minHalfLife..maxHalfLife,
        steps = RESIDUAL_FATIGUE_HALF_LIFE_SLIDER_STEPS,
        displayValue = stringResource(R.string.advanced_residual_fatigue_half_life_hours, halfLife),
        description = stringResource(R.string.advanced_residual_fatigue_half_life_desc),
    )

    var currentGain by remember(uiState.residualFatigueGain) {
        mutableFloatStateOf(uiState.residualFatigueGain)
    }
    val minGain = SettingsDefaults.MIN_RESIDUAL_FATIGUE_GAIN
    val maxGain = SettingsDefaults.MAX_RESIDUAL_FATIGUE_GAIN
    ThresholdSliderItem(
        label = stringResource(R.string.advanced_residual_fatigue_gain_label),
        enabled = controlsEnabled,
        value = currentGain,
        onValueChange = { currentGain = it },
        onValueChangeFinished = {
            onUIEvent(SettingsEvent.ResidualFatigueGainChanged(currentGain))
        },
        valueRange = minGain..maxGain,
        steps = RESIDUAL_FATIGUE_GAIN_SLIDER_STEPS,
        displayValue = stringResource(R.string.advanced_residual_fatigue_gain_value, currentGain),
        description = stringResource(R.string.advanced_residual_fatigue_gain_desc),
    )

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.medium),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(
            onClick = { onUIEvent(SettingsEvent.ResetFatigueToDefaults) },
            enabled = controlsEnabled,
        ) {
            Text(stringResource(R.string.advanced_residual_fatigue_reset_button))
        }
    }
}
