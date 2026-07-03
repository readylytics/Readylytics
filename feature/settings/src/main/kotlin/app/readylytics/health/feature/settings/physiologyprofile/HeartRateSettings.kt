package app.readylytics.health.feature.settings.physiologyprofile

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.components.settings.BirthdayDatePickerField
import app.readylytics.health.core.ui.settings.HeightInputField
import app.readylytics.health.data.preferences.Gender
import app.readylytics.health.feature.settings.HeartRateZonesState
import app.readylytics.health.feature.settings.PhysiologySettingsState
import app.readylytics.health.feature.settings.R
import app.readylytics.health.feature.settings.SettingsEvent
import app.readylytics.health.feature.settings.SettingsExpandState
import app.readylytics.health.feature.settings.common.SettingsConstants
import kotlin.math.roundToInt

@Composable
fun HeartRateZoneSection(
    uiState: HeartRateZonesState,
    physiologyState: PhysiologySettingsState,
    onEvent: (SettingsEvent) -> Unit,
    onPhysiologyEvent: (SettingsEvent) -> Unit,
    expandState: SettingsExpandState,
    onExpandStateChange: (SettingsExpandState) -> Unit,
) {
    var maxHrText by rememberSaveable(uiState.maxHeartRate) {
        mutableStateOf(uiState.maxHeartRate.toString())
    }
    var showBirthdatePicker by rememberSaveable { mutableStateOf(false) }
    var genderExpanded by rememberSaveable { mutableStateOf(false) }

    Column(modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium)) {
        Row(
            modifier = Modifier,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.hr_auto_calculate_label), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.hr_auto_calculate_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = uiState.autoCalculateMaxHr,
                onCheckedChange = { onEvent(SettingsEvent.AutoCalculateMaxHrChanged(it)) },
            )
        }

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.small))

        AnimatedVisibility(visible = uiState.autoCalculateMaxHr) {
            Column {
                BirthdayDatePickerField(
                    birthDate = physiologyState.birthDate,
                    onFieldClick = { showBirthdatePicker = true },
                    onDateSelected = { date ->
                        onPhysiologyEvent(SettingsEvent.BirthdayChanged(date))
                    },
                    showDialog = showBirthdatePicker,
                    onDialogDismiss = { showBirthdatePicker = false },
                )
                Spacer(modifier = Modifier.height(MaterialTheme.spacing.extraSmall))
                Text(
                    stringResource(R.string.hr_age_display, physiologyState.age),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(MaterialTheme.spacing.small))
                GenderSelector(
                    selectedGender = physiologyState.gender,
                    expanded = genderExpanded,
                    onExpandedChange = { genderExpanded = it },
                    onGenderSelected = { onPhysiologyEvent(SettingsEvent.GenderChanged(it)) },
                )
                Spacer(modifier = Modifier.height(MaterialTheme.spacing.small))
                HeightInputField(
                    heightCm = physiologyState.heightCm,
                    onHeightChange = { onPhysiologyEvent(SettingsEvent.HeightChanged(it)) },
                    unitSystem = physiologyState.unitSystem,
                )
            }
        }

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.small))

        OutlinedTextField(
            value = maxHrText,
            onValueChange = {
                maxHrText = it
                onEvent(SettingsEvent.MaxHeartRateChanged(it))
            },
            label = { Text(stringResource(R.string.hr_max_rate_label)) },
            enabled = !uiState.autoCalculateMaxHr,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = {
                if (uiState.autoCalculateMaxHr) {
                    Text(stringResource(R.string.hr_calculated_from_age))
                } else {
                    Text(stringResource(R.string.hr_manual_override))
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.smallMedium))

        Row(
            modifier = Modifier,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.hr_manual_zone_editing_label), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.hr_manual_zone_editing_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = uiState.manualZoneEditing,
                onCheckedChange = { onEvent(SettingsEvent.ManualZoneEditingChanged(it)) },
            )
        }

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.small))

        if (uiState.manualZoneEditing) {
            ZoneEditingSection(uiState = uiState, onEvent = onEvent)
        } else {
            Text(
                stringResource(R.string.hr_calculated_zones_header),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(MaterialTheme.spacing.extraSmall))
            HeartRateZonesDisplay(
                maxHr = uiState.maxHeartRate,
                z1MinP = uiState.zone1MinPercent,
                z1p = uiState.zone1MaxPercent,
                z2p = uiState.zone2MaxPercent,
                z3p = uiState.zone3MaxPercent,
                z4p = uiState.zone4MaxPercent,
            )
        }

        Text(
            stringResource(app.readylytics.health.core.ui.R.string.hr_zones_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = MaterialTheme.spacing.small),
        )
    }
}

@Composable
fun HeartRateZonesDisplay(
    maxHr: Int,
    z1MinP: Float,
    z1p: Float,
    z2p: Float,
    z3p: Float,
    z4p: Float,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
        modifier = Modifier.fillMaxWidth(),
    ) {
        val zones =
            listOf(
                stringResource(R.string.hr_zone_n, 1) to (z1MinP.toDouble()..z1p.toDouble()),
                stringResource(R.string.hr_zone_n, 2) to (z1p.toDouble()..z2p.toDouble()),
                stringResource(R.string.hr_zone_n, 3) to (z2p.toDouble()..z3p.toDouble()),
                stringResource(R.string.hr_zone_n, 4) to (z3p.toDouble()..z4p.toDouble()),
                stringResource(R.string.hr_zone_n, 5) to (z4p.toDouble()..1.00),
            )

        zones.forEach { (name, range) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text =
                        stringResource(
                            R.string.hr_zone_range_display,
                            (maxHr * range.start).roundToInt(),
                            (maxHr * range.endInclusive).roundToInt(),
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
fun ZoneEditingSection(
    uiState: HeartRateZonesState,
    onEvent: (SettingsEvent) -> Unit,
) {
    var z1Min by rememberSaveable { mutableStateOf(uiState.zone1MinBpm.toString()) }
    var z1Max by rememberSaveable { mutableStateOf(uiState.zone1MaxBpm.toString()) }
    var z2Max by rememberSaveable { mutableStateOf(uiState.zone2MaxBpm.toString()) }
    var z3Max by rememberSaveable { mutableStateOf(uiState.zone3MaxBpm.toString()) }
    var z4Max by rememberSaveable { mutableStateOf(uiState.zone4MaxBpm.toString()) }

    val maxHr = uiState.maxHeartRate
    val isValid =
        remember(z1Min, z1Max, z2Max, z3Max, z4Max, maxHr) {
            val vals = listOf(z1Min, z1Max, z2Max, z3Max, z4Max).mapNotNull { it.toIntOrNull() }
            vals.size == 5 &&
                vals[0] in 1..maxHr &&
                vals[1] in 1..maxHr &&
                vals[2] in 1..maxHr &&
                vals[3] in 1..maxHr &&
                vals[4] in 1..maxHr &&
                vals[0] < vals[1] &&
                vals[1] < vals[2] &&
                vals[2] < vals[3] &&
                vals[3] < vals[4] &&
                vals[4] <= maxHr
        }

    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small)) {
        Text(
            stringResource(R.string.hr_zones_section_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )

        ZoneRow(stringResource(R.string.hr_zone_n, 1), z1Min, { z1Min = it }, true, z1Max, { z1Max = it }, true)
        ZoneRow(stringResource(R.string.hr_zone_n, 2), z1Max, null, false, z2Max, { z2Max = it }, true)
        ZoneRow(stringResource(R.string.hr_zone_n, 3), z2Max, null, false, z3Max, { z3Max = it }, true)
        ZoneRow(stringResource(R.string.hr_zone_n, 4), z3Max, null, false, z4Max, { z4Max = it }, true)
        ZoneRow(stringResource(R.string.hr_zone_n, 5), z4Max, null, false, maxHr.toString(), null, false)

        if (isValid) {
            Button(
                onClick = {
                    onEvent(
                        SettingsEvent.ZoneBpmsChanged(
                            z1Min.toInt(),
                            z1Max.toInt(),
                            z2Max.toInt(),
                            z3Max.toInt(),
                            z4Max.toInt(),
                        ),
                    )
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = MaterialTheme.spacing.small),
            ) {
                Text(stringResource(R.string.hr_save_zones_button))
            }
        } else {
            Text(
                stringResource(R.string.error_hr_zones_invalid, maxHr),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
fun ZoneRow(
    zoneLabel: String,
    minValue: String,
    onMinChange: ((String) -> Unit)?,
    minEditable: Boolean,
    maxValue: String,
    onMaxChange: ((String) -> Unit)?,
    maxEditable: Boolean,
) {
    val minValid =
        minValue.toIntOrNull()?.let { it in SettingsConstants.MIN_HEART_RATE..SettingsConstants.MAX_HEART_RATE }
            ?: false
    val maxValid =
        maxValue.toIntOrNull()?.let { it in SettingsConstants.MIN_HEART_RATE..SettingsConstants.MAX_HEART_RATE }
            ?: false

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = MaterialTheme.spacing.extraSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        Text(
            zoneLabel,
            modifier = Modifier.width(60.dp),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )

        CompactOutlinedTextField(
            value = minValue,
            onValueChange = { if (minEditable && onMinChange != null) onMinChange(it) },
            modifier = Modifier.width(72.dp),
            isError = minValue.isNotEmpty() && !minValid,
            enabled = minEditable,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )

        Text("–", style = MaterialTheme.typography.bodySmall)

        CompactOutlinedTextField(
            value = maxValue,
            onValueChange = { if (maxEditable && onMaxChange != null) onMaxChange(it) },
            modifier = Modifier.width(72.dp),
            isError = maxValue.isNotEmpty() && !maxValid,
            enabled = maxEditable,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
}

@Composable
fun CompactOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        modifier =
            modifier
                .background(
                    MaterialTheme.colorScheme.surface,
                    MaterialTheme.shapes.small,
                ).border(
                    width = 1.dp,
                    color =
                        if (isError) {
                            MaterialTheme.colorScheme.error
                        } else if (!enabled) {
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                    shape = MaterialTheme.shapes.small,
                ).padding(horizontal = MaterialTheme.spacing.smallMedium, vertical = MaterialTheme.spacing.small),
        textStyle =
            MaterialTheme.typography.bodySmall.copy(
                color =
                    if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
            ),
        singleLine = true,
        keyboardOptions = keyboardOptions,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenderSelector(
    selectedGender: Gender?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onGenderSelected: (Gender?) -> Unit,
) {
    val genders = Gender.entries

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value =
                selectedGender?.let { stringResource(it.labelRes()) }
                    ?: stringResource(app.readylytics.health.core.ui.R.string.label_not_set),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.label_gender)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier =
                Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            genders.forEach { gender ->
                DropdownMenuItem(
                    text = { Text(stringResource(gender.labelRes())) },
                    onClick =
                        {
                            onGenderSelected(gender)
                            onExpandedChange(false)
                        },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.accessibility_clear)) },
                onClick = {
                    onGenderSelected(null)
                    onExpandedChange(false)
                },
            )
        }
    }
}

@StringRes
private fun Gender.labelRes(): Int =
    when (this) {
        Gender.MALE -> R.string.gender_male
        Gender.FEMALE -> R.string.gender_female
        Gender.OTHER -> R.string.gender_other
        Gender.PREFER_NOT_TO_SAY -> R.string.gender_prefer_not_to_say
    }
