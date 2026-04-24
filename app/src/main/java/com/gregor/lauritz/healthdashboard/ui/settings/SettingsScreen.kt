package com.gregor.lauritz.healthdashboard.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gregor.lauritz.healthdashboard.data.preferences.AppTheme
import com.gregor.lauritz.healthdashboard.data.preferences.BackupSchedule
import com.gregor.lauritz.healthdashboard.data.preferences.SyncPreference
import com.gregor.lauritz.healthdashboard.ui.components.MetricTooltip
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

@Composable
fun SettingsRoute(viewModel: SettingsViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsScreen(uiState = uiState, onEvent = viewModel::onEvent)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    if (uiState.showRestoreConfirmDialog) {
        AlertDialog(
            onDismissRequest = { onEvent(SettingsEvent.RestoreDismissed) },
            title = { Text("Restore from Drive?") },
            text = {
                Text("This will replace all local health data and restart the app. This cannot be undone.")
            },
            confirmButton = {
                Button(onClick = { onEvent(SettingsEvent.RestoreConfirmed) }) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = { onEvent(SettingsEvent.RestoreDismissed) }) { Text("Cancel") }
            },
        )
    }

    var sleepGoalValue by rememberSaveable(uiState.goalSleepHours) {
        mutableFloatStateOf(uiState.goalSleepHours)
    }
    var hrvText by rememberSaveable(uiState.hrvBaselineOverride) {
        mutableStateOf(uiState.hrvBaselineOverride?.toInt()?.toString() ?: "")
    }
    var rhrText by rememberSaveable(uiState.rhrBaselineOverride) {
        mutableStateOf(uiState.rhrBaselineOverride?.toInt()?.toString() ?: "")
    }
    var maxHrText by rememberSaveable(uiState.maxHeartRate) {
        mutableStateOf(uiState.maxHeartRate.toString())
    }
    var birthDayText by rememberSaveable(uiState.birthDay) {
        mutableStateOf(uiState.birthDay.toString())
    }
    var birthMonthText by rememberSaveable(uiState.birthMonth) {
        mutableStateOf(uiState.birthMonth.toString())
    }
    var birthYearText by rememberSaveable(uiState.birthYear) {
        mutableStateOf(uiState.birthYear.toString())
    }
    var beforeMinutesText by rememberSaveable(uiState.restingHrBeforeMinutes) {
        mutableStateOf(uiState.restingHrBeforeMinutes.toString())
    }
    var afterMinutesText by rememberSaveable(uiState.restingHrAfterMinutes) {
        mutableStateOf(uiState.restingHrAfterMinutes.toString())
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        // Goals
        item { SectionHeader("Goals") }
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
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

        // Sync
        item { HorizontalDivider(modifier = Modifier.padding(top = 12.dp)) }
        item { SectionHeader("Sync") }
        item { SyncPreferenceItem(uiState = uiState, onEvent = onEvent) }
        item {
            AnimatedVisibility(visible = uiState.syncPreference == SyncPreference.BY_TIME) {
                SyncIntervalItem(uiState = uiState, onEvent = onEvent)
            }
        }

        // Appearance
        item { HorizontalDivider(modifier = Modifier.padding(top = 8.dp)) }
        item { SectionHeader("Appearance") }
        item { AppThemeItem(uiState = uiState, onEvent = onEvent) }

        // Thresholds
        item { HorizontalDivider(modifier = Modifier.padding(top = 8.dp)) }
        item { SectionHeader("Thresholds") }
        item {
            ThresholdSliderItem(
                label = "HRV Optimal",
                value = uiState.hrvOptimalThreshold,
                onValueChange = { onEvent(SettingsEvent.HrvOptimalThresholdChanged(it)) },
                valueRange = 1.0f..1.2f,
                description = "HRV ratio to baseline to be considered Optimal (e.g. 100-120%).",
            )
        }
        item {
            ThresholdSliderItem(
                label = "HRV Warning",
                value = uiState.hrvWarningThreshold,
                onValueChange = { onEvent(SettingsEvent.HrvWarningThresholdChanged(it)) },
                valueRange = 0.8f..1.0f,
                description = "HRV ratio to baseline to be considered Warning (e.g. 80-100%).",
            )
        }
        item { Spacer(modifier = Modifier.height(8.dp)) }
        item {
            ThresholdSliderItem(
                label = "RHR Optimal",
                value = uiState.rhrOptimalThreshold,
                onValueChange = { onEvent(SettingsEvent.RhrOptimalThresholdChanged(it)) },
                valueRange = 0.8f..1.0f,
                description = "RHR ratio to baseline to be considered Optimal (e.g. 80-100%).",
            )
        }
        item {
            ThresholdSliderItem(
                label = "RHR Warning",
                value = uiState.rhrWarningThreshold,
                onValueChange = { onEvent(SettingsEvent.RhrWarningThresholdChanged(it)) },
                valueRange = 1.0f..1.2f,
                description = "RHR ratio to baseline to be considered Warning (e.g. 100-120%).",
            )
        }

        // Heart Rate Zones
        item { HorizontalDivider(modifier = Modifier.padding(top = 8.dp)) }
        item { SectionHeader("Heart Rate Zones") }
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-calculate Max HR", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Uses age (220 - age) if enabled",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = uiState.autoCalculateMaxHr,
                        onCheckedChange = { onEvent(SettingsEvent.AutoCalculateMaxHrChanged(it)) }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                AnimatedVisibility(visible = uiState.autoCalculateMaxHr) {
                    Column {
                        Text(
                            "Date of Birth",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = birthDayText,
                                onValueChange = { v ->
                                    birthDayText = v.filter { it.isDigit() }.take(2)
                                    val d = birthDayText.toIntOrNull() ?: return@OutlinedTextField
                                    val m = birthMonthText.toIntOrNull() ?: return@OutlinedTextField
                                    val y = birthYearText.toIntOrNull() ?: return@OutlinedTextField
                                    if (d in 1..31 && m in 1..12 && y in 1900..9999) {
                                        onEvent(SettingsEvent.BirthdayChanged(d, m, y))
                                    }
                                },
                                label = { Text("Day") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedTextField(
                                value = birthMonthText,
                                onValueChange = { v ->
                                    birthMonthText = v.filter { it.isDigit() }.take(2)
                                    val d = birthDayText.toIntOrNull() ?: return@OutlinedTextField
                                    val m = birthMonthText.toIntOrNull() ?: return@OutlinedTextField
                                    val y = birthYearText.toIntOrNull() ?: return@OutlinedTextField
                                    if (d in 1..31 && m in 1..12 && y in 1900..9999) {
                                        onEvent(SettingsEvent.BirthdayChanged(d, m, y))
                                    }
                                },
                                label = { Text("Month") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedTextField(
                                value = birthYearText,
                                onValueChange = { v ->
                                    birthYearText = v.filter { it.isDigit() }.take(4)
                                    val d = birthDayText.toIntOrNull() ?: return@OutlinedTextField
                                    val m = birthMonthText.toIntOrNull() ?: return@OutlinedTextField
                                    val y = birthYearText.toIntOrNull() ?: return@OutlinedTextField
                                    if (d in 1..31 && m in 1..12 && y in 1900..9999) {
                                        onEvent(SettingsEvent.BirthdayChanged(d, m, y))
                                    }
                                },
                                label = { Text("Year") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1.4f),
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Age: ${uiState.age} (auto-calculated)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        GenderSelector(
                            selectedGender = uiState.gender,
                            onGenderSelected = { onEvent(SettingsEvent.GenderChanged(it)) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

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
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Manual Zone Editing", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Customize percentage thresholds",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = uiState.manualZoneEditing,
                        onCheckedChange = { onEvent(SettingsEvent.ManualZoneEditingChanged(it)) }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (uiState.manualZoneEditing) {
                    ZoneEditingSection(uiState = uiState, onEvent = onEvent)
                } else {
                    Text(
                        "Calculated Zones",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    HeartRateZonesDisplay(
                        maxHr = uiState.maxHeartRate,
                        z1p = uiState.zone1MaxPercent,
                        z2p = uiState.zone2MaxPercent,
                        z3p = uiState.zone3MaxPercent,
                        z4p = uiState.zone4MaxPercent
                    )
                }

                Text(
                    "Zones are used for TRIMP and workout intensity tracking.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        // Advanced
        item { HorizontalDivider(modifier = Modifier.padding(top = 8.dp)) }
        item { SectionHeader("Advanced") }

        // Baselines
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(
                    "Baseline Overrides",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = hrvText,
                    onValueChange = {
                        hrvText = it
                        onEvent(SettingsEvent.HrvBaselineChanged(it))
                    },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("HRV Baseline (ms)")
                            MetricTooltip(
                                description = "Overrides the 30-day rolling median used in scoring."
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    trailingIcon = {
                        if (hrvText.isNotEmpty()) {
                            IconButton(onClick = {
                                hrvText = ""
                                onEvent(SettingsEvent.HrvBaselineCleared)
                            }) {
                                Icon(Icons.Filled.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = rhrText,
                    onValueChange = {
                        rhrText = it
                        onEvent(SettingsEvent.RhrBaselineChanged(it))
                    },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("RHR Baseline (bpm)")
                            MetricTooltip(
                                description = "Overrides the 30-day rolling median used in scoring."
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    trailingIcon = {
                        if (rhrText.isNotEmpty()) {
                            IconButton(onClick = {
                                rhrText = ""
                                onEvent(SettingsEvent.RhrBaselineCleared)
                            }) {
                                Icon(Icons.Filled.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }

        // Wakeup Resting HR fields
        item {
            OutlinedTextField(
                value = beforeMinutesText,
                onValueChange =
                    { value ->
                        beforeMinutesText = value
                        value.toIntOrNull()?.let { onEvent(SettingsEvent.RestingHrBeforeMinutesChanged(it)) }
                    },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Resting HR: Minutes Before")
                        MetricTooltip(
                            description = "Minutes before sleep end to include in wakeup resting HR calculation (default: 5)."
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
            )
        }
        item { Spacer(modifier = Modifier.height(12.dp)) }
        item {
            OutlinedTextField(
                value = afterMinutesText,
                onValueChange =
                    { value ->
                        afterMinutesText = value
                        value.toIntOrNull()?.let { onEvent(SettingsEvent.RestingHrAfterMinutesChanged(it)) }
                    },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Resting HR: Minutes After")
                        MetricTooltip(
                            description = "Minutes after sleep end to include in wakeup resting HR calculation (default: 15)."
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
            )
        }

        // Cloud Backup
        item { HorizontalDivider(modifier = Modifier.padding(top = 8.dp)) }
        item { SectionHeader("Cloud Backup") }
        item {
            CloudBackupSection(
                uiState = uiState,
                onEvent = onEvent,
                context = context,
            )
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
private fun HeartRateZonesDisplay(
    maxHr: Int,
    z1p: Float,
    z2p: Float,
    z3p: Float,
    z4p: Float
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        val zones = listOf(
            "Zone 1" to (0.50..z1p.toDouble()),
            "Zone 2" to (z1p.toDouble()..z2p.toDouble()),
            "Zone 3" to (z2p.toDouble()..z3p.toDouble()),
            "Zone 4" to (z3p.toDouble()..z4p.toDouble()),
            "Zone 5" to (z4p.toDouble()..1.00)
        )

        zones.forEach { (name, range) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "${(maxHr * range.start).roundToInt()} - ${(maxHr * range.endInclusive).roundToInt()} bpm",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun ZoneEditingSection(
    uiState: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Zone Max Percentages",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )

        ZonePercentageSliderItem(
            label = "Zone 1 Max",
            value = uiState.zone1MaxPercent,
            onValueChange = { onEvent(SettingsEvent.ZonePercentagesChanged(it, uiState.zone2MaxPercent, uiState.zone3MaxPercent, uiState.zone4MaxPercent)) }
        )
        ZonePercentageSliderItem(
            label = "Zone 2 Max",
            value = uiState.zone2MaxPercent,
            onValueChange = { onEvent(SettingsEvent.ZonePercentagesChanged(uiState.zone1MaxPercent, it, uiState.zone3MaxPercent, uiState.zone4MaxPercent)) }
        )
        ZonePercentageSliderItem(
            label = "Zone 3 Max",
            value = uiState.zone3MaxPercent,
            onValueChange = { onEvent(SettingsEvent.ZonePercentagesChanged(uiState.zone1MaxPercent, uiState.zone2MaxPercent, it, uiState.zone4MaxPercent)) }
        )
        ZonePercentageSliderItem(
            label = "Zone 4 Max",
            value = uiState.zone4MaxPercent,
            onValueChange = { onEvent(SettingsEvent.ZonePercentagesChanged(uiState.zone1MaxPercent, uiState.zone2MaxPercent, uiState.zone3MaxPercent, it)) }
        )
    }
}

@Composable
private fun ZonePercentageSliderItem(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "${(value * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0.4f..0.95f,
            steps = 54, // 1% increments
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GenderSelector(
    selectedGender: String?,
    onGenderSelected: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val genders = listOf("Male", "Female", "Other", "Prefer not to say")

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selectedGender ?: "Not set",
            onValueChange = {},
            readOnly = true,
            label = { Text("Gender") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier =
                Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            genders.forEach { gender ->
                DropdownMenuItem(
                    text = { Text(gender) },
                    onClick =
                        {
                            onGenderSelected(gender)
                            expanded = false
                        },
                )
            }
            DropdownMenuItem(
                text = { Text("Clear") },
                onClick = {
                    onGenderSelected(null)
                    expanded = false
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppThemeItem(
    uiState: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        OutlinedTextField(
            value = uiState.appTheme.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text("App Theme") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier =
                Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            AppTheme.entries.forEach { theme ->
                DropdownMenuItem(
                    text = { Text(theme.displayName) },
                    onClick =
                        {
                            onEvent(SettingsEvent.AppThemeChanged(theme))
                            expanded = false
                        },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SyncPreferenceItem(
    uiState: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        OutlinedTextField(
            value = uiState.syncPreference.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Foreground Sync") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier =
                Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            SyncPreference.entries.forEach { pref ->
                DropdownMenuItem(
                    text = { Text(pref.displayName) },
                    onClick =
                        {
                            onEvent(SettingsEvent.SyncPreferenceChanged(pref))
                            expanded = false
                        },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SyncIntervalItem(
    uiState: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        OutlinedTextField(
            value = "${uiState.syncIntervalHours}h",
            onValueChange = {},
            readOnly = true,
            label = { Text("Sync Interval") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier =
                Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            (1..24).forEach { hours ->
                DropdownMenuItem(
                    text = { Text("${hours}h") },
                    onClick =
                        {
                            onEvent(SettingsEvent.SyncIntervalChanged(hours))
                            expanded = false
                        },
                )
            }
        }
    }
}

@Composable
private fun ThresholdSliderItem(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    description: String,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            MetricTooltip(description = description)
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "${(value * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = ((valueRange.endInclusive - valueRange.start) * 100).roundToInt() - 1,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

private fun Float.toSleepHoursText(): String {
    val totalMinutes = (this * 60).roundToInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (minutes == 0) "${hours}h" else "${hours}h ${minutes}m"
}

private val SyncPreference.displayName: String
    get() =
        when (this) {
            SyncPreference.NEVER -> "Never"
            SyncPreference.ALWAYS -> "Always"
            SyncPreference.BY_TIME -> "By Time"
        }

private val AppTheme.displayName: String
    get() =
        when (this) {
            AppTheme.SYSTEM -> "System Default"
            AppTheme.LIGHT -> "Light"
            AppTheme.DARK -> "Dark"
        }

private val BackupSchedule.displayName: String
    get() =
        when (this) {
            BackupSchedule.MANUAL -> "Manual only"
            BackupSchedule.DAILY -> "Daily"
            BackupSchedule.WEEKLY -> "Weekly"
        }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CloudBackupSection(
    uiState: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
    context: android.content.Context,
) {
    val signedIn = uiState.driveEmail != null

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        // Account row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Google Account", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = uiState.driveEmail ?: "Not connected",
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (signedIn) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
            if (signedIn) {
                TextButton(onClick = { onEvent(SettingsEvent.DriveSignOut(context)) }) {
                    Text("Sign Out")
                }
            } else {
                TextButton(onClick = { onEvent(SettingsEvent.DriveSignIn(context)) }) {
                    Text("Sign In")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Backup schedule dropdown
        BackupScheduleItem(uiState = uiState, onEvent = onEvent)

        Spacer(modifier = Modifier.height(8.dp))

        // Last backup timestamp
        val lastBackupText =
            if (uiState.lastBackupTimestamp == 0L) {
                "Never backed up"
            } else {
                "Last backup: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(uiState.lastBackupTimestamp))}"
            }
        Text(
            text = lastBackupText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = { onEvent(SettingsEvent.BackupNow) },
                enabled = signedIn && !uiState.isBackingUp && !uiState.isRestoring,
                modifier = Modifier.weight(1f),
            ) {
                if (uiState.isBackingUp) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Back Up Now")
                }
            }
            Spacer(modifier = Modifier.size(8.dp))
            OutlinedButton(
                onClick = { onEvent(SettingsEvent.RestoreFromDrive) },
                enabled = signedIn && !uiState.isBackingUp && !uiState.isRestoring,
                modifier = Modifier.weight(1f),
            ) {
                if (uiState.isRestoring) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Restore")
                }
            }
        }

        // Inline error display
        if (uiState.driveError != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = uiState.driveError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onEvent(SettingsEvent.DismissDriveError) }) {
                    Text("Dismiss")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackupScheduleItem(
    uiState: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = uiState.backupSchedule.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Auto Backup") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier =
                Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            BackupSchedule.entries.forEach { schedule ->
                DropdownMenuItem(
                    text = { Text(schedule.displayName) },
                    onClick = {
                        onEvent(SettingsEvent.BackupScheduleChanged(schedule))
                        expanded = false
                    },
                )
            }
        }
    }
}
