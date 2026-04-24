package com.gregor.lauritz.healthdashboard.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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

    var sliderValue by rememberSaveable(uiState.goalSleepHours) {
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
                        text = sliderValue.toSleepHoursText(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    onValueChangeFinished = {
                        onEvent(SettingsEvent.GoalSleepHoursChanged(sliderValue))
                    },
                    valueRange = 4f..12f,
                    steps = 15,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // Baselines
        item { HorizontalDivider(modifier = Modifier.padding(top = 4.dp)) }
        item { SectionHeader("Baselines") }
        item {
            OutlinedTextField(
                value = hrvText,
                onValueChange =
                    { value ->
                        hrvText = value
                        onEvent(SettingsEvent.HrvBaselineChanged(value))
                    },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("HRV Baseline Override (ms)")
                        MetricTooltip(
                            description =
                                "HRV in ms. Overrides the 30-day rolling median " +
                                    "used in sleep restoration scoring.",
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                trailingIcon =
                    {
                        if (hrvText.isNotEmpty()) {
                            IconButton(onClick = { onEvent(SettingsEvent.HrvBaselineCleared) }) {
                                Icon(Icons.Filled.Clear, contentDescription = "Clear HRV baseline")
                            }
                        }
                    },
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
                value = rhrText,
                onValueChange =
                    { value ->
                        rhrText = value
                        onEvent(SettingsEvent.RhrBaselineChanged(value))
                    },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("RHR Baseline Override (bpm)")
                        MetricTooltip(
                            description =
                                "Resting heart rate in bpm. Overrides the 30-day " +
                                    "rolling median used in sleep restoration scoring.",
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                trailingIcon =
                    {
                        if (rhrText.isNotEmpty()) {
                            IconButton(onClick = { onEvent(SettingsEvent.RhrBaselineCleared) }) {
                                Icon(Icons.Filled.Clear, contentDescription = "Clear RHR baseline")
                            }
                        }
                    },
                singleLine = true,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
            )
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

        // Advanced
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

        // Advanced
        item { HorizontalDivider(modifier = Modifier.padding(top = 8.dp)) }
        item { SectionHeader("Advanced") }
        item {
            OutlinedTextField(
                value = maxHrText,
                onValueChange =
                    { value ->
                        maxHrText = value
                        onEvent(SettingsEvent.MaxHeartRateChanged(value))
                    },
                label = { Text("Max Heart Rate") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = { Text("150–220 bpm") },
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
                value = beforeMinutesText,
                onValueChange =
                    { value ->
                        beforeMinutesText = value
                        value.toIntOrNull()?.let { onEvent(SettingsEvent.RestingHrBeforeMinutesChanged(it)) }
                    },
                label = { Text("Wakeup Resting HR: Minutes Before") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = { Text("Minutes before sleep end (default: 5)") },
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
                label = { Text("Wakeup Resting HR: Minutes After") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = { Text("Minutes after sleep end (default: 15)") },
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
