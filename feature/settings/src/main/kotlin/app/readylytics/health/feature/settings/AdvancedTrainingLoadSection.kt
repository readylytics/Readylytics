package app.readylytics.health.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.model.domain.scoring.TrimpModel

@Composable
fun TrainingLoadSubsection(
    uiState: UIState,
    controlsEnabled: Boolean,
    isResyncing: Boolean,
    onUIEvent: (SettingsEvent) -> Unit,
) {
    Column {
        Text(
            stringResource(R.string.advanced_training_load_label),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium),
        )
        Spacer(modifier = Modifier.height(MaterialTheme.spacing.small))
        TrimpModelDropdown(
            trimpModel = uiState.trimpModel,
            controlsEnabled = controlsEnabled,
            isResyncing = isResyncing,
            onModelSelected = { onUIEvent(SettingsEvent.TrimpModelChanged(it)) },
        )
        Spacer(modifier = Modifier.height(MaterialTheme.spacing.small))
        TrimpModelParams(
            uiState = uiState,
            controlsEnabled = controlsEnabled,
            onUIEvent = onUIEvent,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrimpModelDropdown(
    trimpModel: TrimpModel,
    controlsEnabled: Boolean,
    isResyncing: Boolean,
    onModelSelected: (TrimpModel) -> Unit,
) {
    val trimpModelOptions =
        listOf(
            TrimpModel.BANISTER to stringResource(R.string.advanced_trimp_banister),
            TrimpModel.CHENG to stringResource(R.string.advanced_trimp_cheng),
            TrimpModel.I_TRIMP to stringResource(R.string.advanced_trimp_itrimp),
        )
    val selectedModelLabel =
        trimpModelOptions.firstOrNull { it.first == trimpModel }?.second
            ?: stringResource(R.string.advanced_trimp_banister)
    var trimpDropdownExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(isResyncing) {
        if (isResyncing) trimpDropdownExpanded = false
    }
    ExposedDropdownMenuBox(
        expanded = trimpDropdownExpanded,
        onExpandedChange = { if (controlsEnabled) trimpDropdownExpanded = it },
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.medium),
    ) {
        OutlinedTextField(
            value = selectedModelLabel,
            onValueChange = {},
            readOnly = true,
            enabled = controlsEnabled,
            label = { Text(stringResource(R.string.advanced_training_load_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = trimpDropdownExpanded) },
            modifier =
                Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
                    .fillMaxWidth(),
        )
        DropdownMenu(
            expanded = trimpDropdownExpanded && controlsEnabled,
            onDismissRequest = { trimpDropdownExpanded = false },
            modifier = Modifier.exposedDropdownSize(),
        ) {
            trimpModelOptions.forEach { (model, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onModelSelected(model)
                        trimpDropdownExpanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun TrimpModelParams(
    uiState: UIState,
    controlsEnabled: Boolean,
    onUIEvent: (SettingsEvent) -> Unit,
) {
    when (uiState.trimpModel) {
        TrimpModel.BANISTER -> {
            var m by remember(uiState.banisterMultiplier) { mutableFloatStateOf(uiState.banisterMultiplier) }
            ThresholdSliderItem(
                label = stringResource(R.string.advanced_banister_multiplier_label),
                value = m,
                onValueChange = { m = it },
                onValueChangeFinished = { onUIEvent(SettingsEvent.BanisterMultiplierChanged(m)) },
                onReset = { onUIEvent(SettingsEvent.ResetTrimpToProfileDefaults) },
                valueRange = 0.5f..2.5f,
                steps = 40,
                displayValue = "%.2f".format(m),
                description = stringResource(R.string.advanced_banister_multiplier_desc),
                enabled = controlsEnabled,
            )
        }
        TrimpModel.CHENG -> {
            var beta by remember(uiState.chengBeta) { mutableFloatStateOf(uiState.chengBeta) }
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
                enabled = controlsEnabled,
            )
        }
        TrimpModel.I_TRIMP -> {
            var b by remember(uiState.itrimB) { mutableFloatStateOf(uiState.itrimB) }
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
                enabled = controlsEnabled,
            )
        }
    }
}
