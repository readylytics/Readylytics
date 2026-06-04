package com.gregor.lauritz.healthdashboard.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.domain.scoring.TrimpModel
import com.gregor.lauritz.healthdashboard.domain.validation.SettingsValidators
import com.gregor.lauritz.healthdashboard.domain.validation.ValidationResult
import com.gregor.lauritz.healthdashboard.ui.components.MetricTooltip
import com.gregor.lauritz.healthdashboard.ui.settings.common.SettingsConstants
import androidx.compose.ui.res.stringResource
import com.gregor.lauritz.healthdashboard.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSettingsSection(
    sleepState: SleepSettingsState,
    paiScalingFactor: Float,
    trimpModel: TrimpModel,
    banisterMultiplier: Float,
    chengBeta: Float,
    itrimB: Float,
    onEvent: (SettingsEvent) -> Unit,
    onPhysiologyEvent: (SettingsEvent) -> Unit,
    onUIEvent: (SettingsEvent) -> Unit,
) {
    val trimpModelOptions = listOf(
        TrimpModel.BANISTER to stringResource(R.string.advanced_trimp_banister),
        TrimpModel.CHENG to stringResource(R.string.advanced_trimp_cheng),
        TrimpModel.I_TRIMP to stringResource(R.string.advanced_trimp_itrimp),
    )

    var hrvText by remember(sleepState.hrvBaselineOverride) {
        mutableStateOf(sleepState.hrvBaselineOverride?.toInt()?.toString() ?: "")
    }
    var rhrText by remember(sleepState.rhrBaselineOverride) {
        mutableStateOf(sleepState.rhrBaselineOverride?.toInt()?.toString() ?: "")
    }

    var percentileValue by remember(sleepState.restingHrPercentile) {
        mutableIntStateOf(sleepState.restingHrPercentile)
    }

    val hrvValidation = SettingsValidators.HRV_BASELINE_RULE.validate(hrvText)
    val rhrValidation = SettingsValidators.RHR_BASELINE_RULE.validate(rhrText)

    Column {
        Column(modifier = Modifier.padding(horizontal = SettingsConstants.HORIZONTAL_PADDING)) {
            Text(
                stringResource(R.string.advanced_baseline_overrides_title),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(bottom = SettingsConstants.VERTICAL_SPACER),
            )
            OutlinedTextField(
                value = hrvText,
                onValueChange = { value ->
                    hrvText = value
                    val validation = SettingsValidators.HRV_BASELINE_RULE.validate(value)
                    if (validation is ValidationResult.Valid) {
                        value.toIntOrNull()?.let { onEvent(SettingsEvent.HrvBaselineChanged(it.toString())) }
                    }
                },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.advanced_hrv_baseline_label))
                        MetricTooltip(
                            description = stringResource(R.string.advanced_baseline_override_tooltip),
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = hrvText.isNotEmpty() && hrvValidation is ValidationResult.Invalid,
                supportingText = {
                    if (hrvValidation is ValidationResult.Invalid) Text(hrvValidation.message)
                },
                trailingIcon = {
                    if (hrvText.isNotEmpty()) {
                        IconButton(onClick = {
                            hrvText = ""
                            onEvent(SettingsEvent.HrvBaselineCleared)
                        }) {
                            Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.accessibility_clear))
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(SettingsConstants.VERTICAL_SPACER))
            OutlinedTextField(
                value = rhrText,
                onValueChange = { value ->
                    rhrText = value
                    val validation = SettingsValidators.RHR_BASELINE_RULE.validate(value)
                    if (validation is ValidationResult.Valid) {
                        value.toIntOrNull()?.let { onEvent(SettingsEvent.RhrBaselineChanged(it.toString())) }
                    }
                },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.advanced_rhr_baseline_label))
                        MetricTooltip(
                            description = stringResource(R.string.advanced_baseline_override_tooltip),
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = rhrText.isNotEmpty() && rhrValidation is ValidationResult.Invalid,
                supportingText = {
                    if (rhrValidation is ValidationResult.Invalid) Text(rhrValidation.message)
                },
                trailingIcon = {
                    if (rhrText.isNotEmpty()) {
                        IconButton(onClick = {
                            rhrText = ""
                            onEvent(SettingsEvent.RhrBaselineCleared)
                        }) {
                            Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.accessibility_clear))
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(modifier = Modifier.height(SettingsConstants.VERTICAL_SPACER_LARGE))

        Column(modifier = Modifier.padding(horizontal = SettingsConstants.HORIZONTAL_PADDING)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.advanced_resting_hr_percentile_label))
                MetricTooltip(
                    description = stringResource(R.string.advanced_resting_hr_percentile_tooltip),
                )
            }
            Spacer(modifier = Modifier.height(SettingsConstants.VERTICAL_SPACER))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = percentileValue.toFloat(),
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
                    modifier = Modifier.padding(start = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Spacer(modifier = Modifier.height(SettingsConstants.VERTICAL_SPACER_LARGE))

        var paiScaling by remember(paiScalingFactor) { mutableFloatStateOf(paiScalingFactor) }
        ThresholdSliderItem(
            label = stringResource(R.string.advanced_pai_scaling_label),
            value = paiScaling,
            onValueChange = { paiScaling = it },
            onValueChangeFinished = { onUIEvent(SettingsEvent.PaiScalingFactorChanged(paiScaling)) },
            onReset = { onPhysiologyEvent(SettingsEvent.ResetPaiScalingFactor) },
            valueRange = 0.1f..0.3f,
            steps = 20,
            displayValue = "%.2f".format(paiScaling),
            description = stringResource(R.string.advanced_pai_scaling_tooltip),
        )

        Spacer(modifier = Modifier.height(SettingsConstants.VERTICAL_SPACER_LARGE))
        Text(
            stringResource(R.string.advanced_training_load_label),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = SettingsConstants.HORIZONTAL_PADDING),
        )
        Spacer(modifier = Modifier.height(SettingsConstants.VERTICAL_SPACER))
        val selectedModelLabel = trimpModelOptions.firstOrNull { it.first == trimpModel }?.second ?: stringResource(R.string.advanced_trimp_banister)
        var trimpDropdownExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = trimpDropdownExpanded,
            onExpandedChange = { trimpDropdownExpanded = !trimpDropdownExpanded },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SettingsConstants.HORIZONTAL_PADDING),
        ) {
            OutlinedTextField(
                value = selectedModelLabel,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.advanced_training_load_label)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = trimpDropdownExpanded) },
                modifier =
                    Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
                        .fillMaxWidth(),
            )
            ExposedDropdownMenu(
                expanded = trimpDropdownExpanded,
                onDismissRequest = { trimpDropdownExpanded = false },
            ) {
                trimpModelOptions.forEach { (model, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            onUIEvent(SettingsEvent.TrimpModelChanged(model))
                            trimpDropdownExpanded = false
                        },
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(SettingsConstants.VERTICAL_SPACER))
        when (trimpModel) {
            TrimpModel.BANISTER -> {
                var multiplier by remember(banisterMultiplier) { mutableFloatStateOf(banisterMultiplier) }
                ThresholdSliderItem(
                    label = stringResource(R.string.advanced_banister_multiplier_label),
                    value = multiplier,
                    onValueChange = { multiplier = it },
                    onValueChangeFinished = { onUIEvent(SettingsEvent.BanisterMultiplierChanged(multiplier)) },
                    onReset = { onUIEvent(SettingsEvent.ResetTrimpToProfileDefaults) },
                    valueRange = 0.5f..2.5f,
                    steps = 40,
                    displayValue = "%.2f".format(multiplier),
                    description = stringResource(R.string.advanced_banister_multiplier_desc),
                )
            }
            TrimpModel.CHENG -> {
                var beta by remember(chengBeta) { mutableFloatStateOf(chengBeta) }
                ThresholdSliderItem(
                    label = stringResource(R.string.advanced_cheng_beta_label),
                    value = beta,
                    onValueChange = { beta = it },
                    onValueChangeFinished = { onUIEvent(SettingsEvent.ChengBetaChanged(beta)) },
                    onReset = { onUIEvent(SettingsEvent.ResetTrimpToProfileDefaults) },
                    valueRange = 0.04f..0.12f,
                    steps = 16,
                    displayValue = "%.3f".format(beta),
                    description = stringResource(R.string.advanced_cheng_beta_desc),
                )
            }
            TrimpModel.I_TRIMP -> {
                var b by remember(itrimB) { mutableFloatStateOf(itrimB) }
                ThresholdSliderItem(
                    label = stringResource(R.string.advanced_itrimp_b_factor_label),
                    value = b,
                    onValueChange = { b = it },
                    onValueChangeFinished = { onUIEvent(SettingsEvent.ItrimBChanged(b)) },
                    onReset = { onUIEvent(SettingsEvent.ResetTrimpToProfileDefaults) },
                    valueRange = 1.0f..4.5f,
                    steps = 35,
                    displayValue = "%.1f".format(b),
                    description = stringResource(R.string.advanced_itrimp_b_factor_desc),
                )
            }
        }
    }
}
