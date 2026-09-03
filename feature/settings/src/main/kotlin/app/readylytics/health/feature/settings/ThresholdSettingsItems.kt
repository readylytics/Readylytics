package app.readylytics.health.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.readylytics.health.feature.settings.R

@Composable
internal fun HrvOptimalThresholdItem(
    value: Float,
    controlsEnabled: Boolean,
    onEvent: (SettingsEvent) -> Unit,
) {
    var current by remember(value) { mutableFloatStateOf(value) }
    ThresholdSliderItem(
        label = stringResource(R.string.threshold_hrv_optimal_label),
        enabled = controlsEnabled,
        value = current,
        onValueChange = { current = it },
        onValueChangeFinished = { onEvent(SettingsEvent.HrvOptimalThresholdChanged(current)) },
        valueRange = 1.0f..1.2f,
        description = stringResource(R.string.threshold_hrv_optimal_desc),
    )
}

@Composable
internal fun HrvWarningThresholdItem(
    value: Float,
    controlsEnabled: Boolean,
    onEvent: (SettingsEvent) -> Unit,
) {
    var current by remember(value) { mutableFloatStateOf(value) }
    ThresholdSliderItem(
        label = stringResource(R.string.threshold_hrv_warning_label),
        enabled = controlsEnabled,
        value = current,
        onValueChange = { current = it },
        onValueChangeFinished = { onEvent(SettingsEvent.HrvWarningThresholdChanged(current)) },
        valueRange = 0.8f..1.0f,
        description = stringResource(R.string.threshold_hrv_warning_desc),
    )
}

@Composable
internal fun RhrOptimalThresholdItem(
    value: Float,
    controlsEnabled: Boolean,
    onEvent: (SettingsEvent) -> Unit,
) {
    var current by remember(value) { mutableFloatStateOf(value) }
    ThresholdSliderItem(
        label = stringResource(R.string.threshold_rhr_optimal_label),
        enabled = controlsEnabled,
        value = current,
        onValueChange = { current = it },
        onValueChangeFinished = { onEvent(SettingsEvent.RhrOptimalThresholdChanged(current)) },
        valueRange = 0.8f..1.0f,
        description = stringResource(R.string.threshold_rhr_optimal_desc),
    )
}

@Composable
internal fun RhrWarningThresholdItem(
    value: Float,
    controlsEnabled: Boolean,
    onEvent: (SettingsEvent) -> Unit,
) {
    var current by remember(value) { mutableFloatStateOf(value) }
    ThresholdSliderItem(
        label = stringResource(R.string.threshold_rhr_warning_label),
        enabled = controlsEnabled,
        value = current,
        onValueChange = { current = it },
        onValueChangeFinished = { onEvent(SettingsEvent.RhrWarningThresholdChanged(current)) },
        valueRange = 1.0f..1.2f,
        description = stringResource(R.string.threshold_rhr_warning_desc),
    )
}

@Composable
internal fun BodyTempElevatedThresholdItem(
    value: Float,
    controlsEnabled: Boolean,
    onEvent: (SettingsEvent) -> Unit,
) {
    var current by remember(value) { mutableFloatStateOf(value) }
    ThresholdSliderItem(
        label = stringResource(R.string.threshold_body_temp_elevated_label),
        enabled = controlsEnabled,
        value = current,
        onValueChange = { current = it },
        onValueChangeFinished = { onEvent(SettingsEvent.BodyTempElevatedThresholdChanged(current)) },
        valueRange = 0.25f..1.5f,
        steps = 4,
        displayValue = stringResource(R.string.threshold_body_temp_elevated_value, current),
        description = stringResource(R.string.threshold_body_temp_elevated_desc),
    )
}

@Composable
internal fun ConsistencyEvaluationPeriodItem(
    days: Int,
    controlsEnabled: Boolean,
    onEvent: (SettingsEvent) -> Unit,
) {
    var value by remember(days) { mutableFloatStateOf(days.toFloat()) }
    ThresholdSliderItem(
        label = stringResource(R.string.threshold_evaluation_period_label),
        enabled = controlsEnabled,
        value = value,
        onValueChange = { value = it },
        onValueChangeFinished = { onEvent(SettingsEvent.ConsistencyEvaluationDaysChanged(value.toInt())) },
        valueRange = 3f..14f,
        steps = 10,
        displayValue = pluralStringResource(R.plurals.threshold_consistency_days, value.toInt(), value.toInt()),
        description = stringResource(R.string.threshold_evaluation_period_desc),
    )
}

@Composable
internal fun ConsistencyBaselineWindowItem(
    sessions: Int,
    controlsEnabled: Boolean,
    onEvent: (SettingsEvent) -> Unit,
) {
    var value by remember(sessions) { mutableFloatStateOf(sessions.toFloat()) }
    ThresholdSliderItem(
        label = stringResource(R.string.threshold_baseline_window_label),
        enabled = controlsEnabled,
        value = value,
        onValueChange = { value = it },
        onValueChangeFinished = { onEvent(SettingsEvent.ConsistencyBaselineDaysChanged(value.toInt())) },
        valueRange = 3f..30f,
        steps = 26,
        displayValue = pluralStringResource(R.plurals.threshold_consistency_sessions, value.toInt(), value.toInt()),
        description = stringResource(R.string.threshold_baseline_window_desc),
    )
}
