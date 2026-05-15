package com.gregor.lauritz.healthdashboard.ui.settings.physiologyprofile

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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.data.preferences.Gender
import com.gregor.lauritz.healthdashboard.domain.validation.SettingsValidators
import com.gregor.lauritz.healthdashboard.domain.validation.ValidationResult
import com.gregor.lauritz.healthdashboard.ui.settings.HeartRateZonesState
import com.gregor.lauritz.healthdashboard.ui.settings.PhysiologySettingsState
import com.gregor.lauritz.healthdashboard.ui.settings.SettingsEvent
import com.gregor.lauritz.healthdashboard.ui.settings.SettingsExpandState
import com.gregor.lauritz.healthdashboard.ui.settings.common.SettingsConstants
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
    var birthDayText by rememberSaveable(physiologyState.birthDay) {
        mutableStateOf(physiologyState.birthDay.toString())
    }
    var birthMonthText by rememberSaveable(physiologyState.birthMonth) {
        mutableStateOf(physiologyState.birthMonth.toString())
    }
    var birthYearText by rememberSaveable(physiologyState.birthYear) {
        mutableStateOf(physiologyState.birthYear.toString())
    }
    var genderExpanded by rememberSaveable { mutableStateOf(false) }

    Column(modifier = Modifier.padding(horizontal = SettingsConstants.HORIZONTAL_PADDING)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Auto-calculate Max HR", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Uses age (220 - age) if enabled",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = uiState.autoCalculateMaxHr,
                onCheckedChange = { onEvent(SettingsEvent.AutoCalculateMaxHrChanged(it)) },
            )
        }

        Spacer(modifier = Modifier.height(SettingsConstants.VERTICAL_SPACER))

        AnimatedVisibility(visible = uiState.autoCalculateMaxHr) {
            Column {
                Text(
                    "Date of Birth",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(SettingsConstants.VERTICAL_SPACER_SMALL))
                BirthdayInputRow(
                    dayText = birthDayText,
                    onDayChange = { birthDayText = it },
                    monthText = birthMonthText,
                    onMonthChange = { birthMonthText = it },
                    yearText = birthYearText,
                    onYearChange = { birthYearText = it },
                    onBirthdayValid = { d, m, y ->
                        onPhysiologyEvent(SettingsEvent.BirthdayChanged(d, m, y))
                    },
                )
                Spacer(modifier = Modifier.height(SettingsConstants.VERTICAL_SPACER_SMALL))
                Text(
                    "Age: ${physiologyState.age} (auto-calculated)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(SettingsConstants.VERTICAL_SPACER))
                GenderSelector(
                    selectedGender = physiologyState.gender,
                    expanded = genderExpanded,
                    onExpandedChange = { genderExpanded = it },
                    onGenderSelected = { onPhysiologyEvent(SettingsEvent.GenderChanged(it)) },
                )
            }
        }

        Spacer(modifier = Modifier.height(SettingsConstants.VERTICAL_SPACER))

        OutlinedTextField(
            value = maxHrText,
            onValueChange = {
                maxHrText = it
                onEvent(SettingsEvent.MaxHeartRateChanged(it))
            },
            label = { Text("Max Heart Rate (bpm)") },
            enabled = !uiState.autoCalculateMaxHr,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = {
                if (uiState.autoCalculateMaxHr) {
                    Text("Calculated from age")
                } else {
                    Text("Manual override")
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(SettingsConstants.VERTICAL_SPACER_LARGE))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Manual Zone Editing", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Customize percentage thresholds",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = uiState.manualZoneEditing,
                onCheckedChange = { onEvent(SettingsEvent.ManualZoneEditingChanged(it)) },
            )
        }

        Spacer(modifier = Modifier.height(SettingsConstants.VERTICAL_SPACER))

        if (uiState.manualZoneEditing) {
            ZoneEditingSection(uiState = uiState, onEvent = onEvent)
        } else {
            Text(
                "Calculated Zones",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(SettingsConstants.VERTICAL_SPACER_SMALL))
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
            "Zones are used for TRIMP and workout intensity tracking.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = SettingsConstants.VERTICAL_SPACER),
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
        verticalArrangement = Arrangement.spacedBy(SettingsConstants.VERTICAL_SPACER_SMALL),
        modifier = Modifier.fillMaxWidth(),
    ) {
        val zones =
            listOf(
                "Zone 1" to (z1MinP.toDouble()..z1p.toDouble()),
                "Zone 2" to (z1p.toDouble()..z2p.toDouble()),
                "Zone 3" to (z2p.toDouble()..z3p.toDouble()),
                "Zone 4" to (z3p.toDouble()..z4p.toDouble()),
                "Zone 5" to (z4p.toDouble()..1.00),
            )

        zones.forEach { (name, range) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "${(maxHr * range.start).roundToInt()} - ${(maxHr * range.endInclusive).roundToInt()} bpm",
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

    Column(verticalArrangement = Arrangement.spacedBy(SettingsConstants.VERTICAL_SPACER)) {
        Text(
            "Heart Rate Zones (bpm)",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )

        ZoneRow("Zone 1", z1Min, { z1Min = it }, true, z1Max, { z1Max = it }, true)
        ZoneRow("Zone 2", z1Max, null, false, z2Max, { z2Max = it }, true)
        ZoneRow("Zone 3", z2Max, null, false, z3Max, { z3Max = it }, true)
        ZoneRow("Zone 4", z3Max, null, false, z4Max, { z4Max = it }, true)
        ZoneRow("Zone 5", z4Max, null, false, maxHr.toString(), null, false)

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
                        .padding(top = SettingsConstants.VERTICAL_SPACER),
            ) {
                Text("Save Zone Boundaries")
            }
        } else {
            Text(
                "Invalid: All values must be 1–$maxHr bpm and strictly increasing",
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
                .padding(vertical = SettingsConstants.VERTICAL_SPACER_SMALL),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SettingsConstants.VERTICAL_SPACER),
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
                    RoundedCornerShape(8.dp),
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
                    shape = RoundedCornerShape(8.dp),
                ).padding(horizontal = 12.dp, vertical = SettingsConstants.VERTICAL_SPACER),
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

@Composable
private fun BirthdayInputRow(
    dayText: String,
    onDayChange: (String) -> Unit,
    monthText: String,
    onMonthChange: (String) -> Unit,
    yearText: String,
    onYearChange: (String) -> Unit,
    onBirthdayValid: (Int, Int, Int) -> Unit,
) {
    val dayValidation = SettingsValidators.BIRTHDAY_DAY_RULE.validate(dayText)
    val monthValidation = SettingsValidators.BIRTHDAY_MONTH_RULE.validate(monthText)
    val yearValidation = SettingsValidators.BIRTHDAY_YEAR_RULE.validate(yearText)

    fun tryValidateAndEmit() {
        val d = dayText.toIntOrNull() ?: return
        val m = monthText.toIntOrNull() ?: return
        val y = yearText.toIntOrNull() ?: return
        val dayValid = dayValidation is ValidationResult.Valid
        val monthValid = monthValidation is ValidationResult.Valid
        val yearValid = yearValidation is ValidationResult.Valid
        if (dayValid && monthValid && yearValid) {
            onBirthdayValid(d, m, y)
        }
    }

    Row(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = dayText,
            onValueChange = { v ->
                onDayChange(v.filter { it.isDigit() }.take(2))
                tryValidateAndEmit()
            },
            label = { Text("Day") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            isError = dayText.isNotEmpty() && dayValidation is ValidationResult.Invalid,
            supportingText = {
                if (dayValidation is ValidationResult.Invalid) Text(dayValidation.message)
            },
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(SettingsConstants.VERTICAL_SPACER))
        OutlinedTextField(
            value = monthText,
            onValueChange = { v ->
                onMonthChange(v.filter { it.isDigit() }.take(2))
                tryValidateAndEmit()
            },
            label = { Text("Month") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            isError = monthText.isNotEmpty() && monthValidation is ValidationResult.Invalid,
            supportingText = {
                if (monthValidation is ValidationResult.Invalid) Text(monthValidation.message)
            },
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(SettingsConstants.VERTICAL_SPACER))
        OutlinedTextField(
            value = yearText,
            onValueChange = { v ->
                onYearChange(v.filter { it.isDigit() }.take(4))
                tryValidateAndEmit()
            },
            label = { Text("Year") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            isError = yearText.isNotEmpty() && yearValidation is ValidationResult.Invalid,
            supportingText = {
                if (yearValidation is ValidationResult.Invalid) Text(yearValidation.message)
            },
            modifier = Modifier.weight(1.4f),
        )
    }
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
            value = selectedGender?.displayName ?: "Not set",
            onValueChange = {},
            readOnly = true,
            label = { Text("Gender") },
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
                    text = { Text(gender.displayName) },
                    onClick =
                        {
                            onGenderSelected(gender)
                            onExpandedChange(false)
                        },
                )
            }
            DropdownMenuItem(
                text = { Text("Clear") },
                onClick = {
                    onGenderSelected(null)
                    onExpandedChange(false)
                },
            )
        }
    }
}
