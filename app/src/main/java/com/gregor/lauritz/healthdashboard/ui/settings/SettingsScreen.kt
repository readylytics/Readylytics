package com.gregor.lauritz.healthdashboard.ui.settings

import android.os.Parcelable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import com.gregor.lauritz.healthdashboard.ui.components.DropdownPreferenceItem
import com.gregor.lauritz.healthdashboard.ui.components.MetricTooltip
import com.gregor.lauritz.healthdashboard.ui.components.SectionHeader
import kotlinx.parcelize.Parcelize
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

@Parcelize
data class SettingsExpandState(
    val genderExpanded: Boolean = false,
) : Parcelable

data class SettingsSectionMetadata(
    val id: String,
    val name: String,
    val keywords: List<String>,
)

val settingsSections = listOf(
    SettingsSectionMetadata(
        id = "cloud_data_sync",
        name = "Cloud & Data",
        keywords = listOf("cloud", "backup", "drive", "data", "retention", "resync", "google account", "health connect", "sync", "foreground")
    ),
    SettingsSectionMetadata(
        id = "baselines_thresholds",
        name = "Baselines & Thresholds",
        keywords = listOf("step", "goal", "sleep", "hrv", "rhr", "heart rate", "zone", "baseline", "threshold", "consistency")
    ),
    SettingsSectionMetadata(
        id = "display",
        name = "Display",
        keywords = listOf("appearance", "theme")
    ),
    SettingsSectionMetadata(
        id = "advanced",
        name = "Advanced",
        keywords = listOf("advanced", "override", "pai", "resting", "hr timing")
    ),
)

fun sectionMatches(section: SettingsSectionMetadata, query: String): Boolean {
    if (query.isBlank()) return true
    val lowerQuery = query.lowercase()
    return section.name.lowercase().contains(lowerQuery) ||
            section.keywords.any { it.contains(lowerQuery) }
}

@Composable
fun SettingsRoute(viewModel: SettingsViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsScreen(uiState = uiState, onEvent = viewModel::onEvent, viewModel = viewModel)

    // Loading dialog during resync - appears on top of everything including tab bar
    if (uiState.isResyncing) {
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Syncing health data...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// Settings grouped into 4 collapsible sections:
// 1. Cloud & Data: Cloud Backup, Data Management, Health Connect Sync
// 2. Baselines & Thresholds: Daily Step Goal, Sleep, Heart Rate Zones, Thresholds
// 3. Display: Appearance
// 4. Advanced: Advanced (baseline overrides, PAI scaling, resting HR timing)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var expandState by rememberSaveable { mutableStateOf(SettingsExpandState()) }
    var searchQuery by rememberSaveable { mutableStateOf("") }

    val matchingSections = settingsSections.filter { sectionMatches(it, searchQuery) }
    val shouldExpandSection = { sectionId: String ->
        searchQuery.isNotBlank() && matchingSections.any { it.id == sectionId }
    }

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

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        item(key = "search") {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search settings...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = "Search") },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear")
                        }
                    }
                },
                shape = RoundedCornerShape(16.dp),
                singleLine = true,
            )
        }
        // Cloud & Data & Health Connect
        if (matchingSections.any { it.id == "cloud_data_sync" }) {
            item(key = "header_cloud_data_sync") {
                M3CollapsibleSection(
                    header = "Cloud & Data",
                    expanded = !uiState.collapseCloudData,
                    onExpandedChange = { onEvent(SettingsEvent.CollapseCloudDataChanged(!it)) }
                ) {
                    Column {
                        SectionHeader("Cloud Backup")
                        CloudBackupSection(
                            uiState = uiState,
                            onEvent = onEvent,
                            viewModel = viewModel,
                            context = context,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        SectionHeader("Data Management")
                        DataManagementSection(uiState = uiState, onEvent = onEvent)
                        Spacer(modifier = Modifier.height(12.dp))
                        SectionHeader("Health Connect")
                        SyncSettingsSection(uiState = uiState, onEvent = onEvent)
                    }
                }
            }
            item(key = "divider_after_cloud_data_sync") { HorizontalDivider(modifier = Modifier.padding(top = 12.dp)) }
        }

        // Baselines & Thresholds
        if (matchingSections.any { it.id == "baselines_thresholds" }) {
            item(key = "header_baselines") {
                M3CollapsibleSection(
                    header = "Baselines & Thresholds",
                    expanded = !uiState.collapseBaselinesThresholds,
                    onExpandedChange = { onEvent(SettingsEvent.CollapseBaselinesThresholdsChanged(!it)) }
                ) {
                    Column {
                        SectionHeader("Daily Step Goal")
                        ActivitySettingsSection(uiState = uiState, onEvent = onEvent)
                        Spacer(modifier = Modifier.height(12.dp))
                        SectionHeader("Sleep")
                        SleepSettingsSection(uiState = uiState, onEvent = onEvent)
                        Spacer(modifier = Modifier.height(12.dp))
                        SectionHeader("Heart Rate Zones")
                        HeartRateZoneSection(
                            uiState = uiState,
                            onEvent = onEvent,
                            expandState = expandState,
                            onExpandStateChange = { expandState = it }
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        SectionHeader("Thresholds")
                        ThresholdSettingsSection(uiState = uiState, onEvent = onEvent)
                    }
                }
            }
            item(key = "divider_after_baselines") { HorizontalDivider(modifier = Modifier.padding(top = 12.dp)) }
        }

        // Display
        if (matchingSections.any { it.id == "display" }) {
            item(key = "header_display") {
                M3CollapsibleSection(
                    header = "Display",
                    expanded = !uiState.collapseDisplay,
                    onExpandedChange = { onEvent(SettingsEvent.CollapseDisplayChanged(!it)) }
                ) {
                    AppThemeItem(uiState = uiState, onEvent = onEvent)
                }
            }
            item(key = "divider_after_display") { HorizontalDivider(modifier = Modifier.padding(top = 12.dp)) }
        }

        // Advanced
        if (matchingSections.any { it.id == "advanced" }) {
            item(key = "header_advanced") {
                M3CollapsibleSection(
                    header = "Advanced",
                    expanded = !uiState.collapseAdvanced,
                    onExpandedChange = { onEvent(SettingsEvent.CollapseAdvancedChanged(!it)) }
                ) {
                    AdvancedSettingsSection(uiState = uiState, onEvent = onEvent)
                }
            }
        }

        item(key = "spacer_bottom") { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
private fun SyncSettingsSection(
    uiState: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
) {
    Column {
        SyncPreferenceItem(uiState = uiState, onEvent = onEvent)
        AnimatedVisibility(visible = uiState.syncPreference == SyncPreference.BY_TIME) {
            SyncIntervalItem(uiState = uiState, onEvent = onEvent)
        }
    }
}

@Composable
private fun DataManagementSection(
    uiState: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
) {
    var retentionDays by remember(uiState.retentionDays) {
        mutableFloatStateOf(uiState.retentionDays.toFloat())
    }

    Column {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Retention Enabled", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.weight(1f))
                Switch(
                    checked = uiState.retentionDaysEnabled,
                    onCheckedChange = { onEvent(SettingsEvent.RetentionDaysEnabledChanged(it)) }
                )
            }
        }

        AnimatedVisibility(visible = uiState.retentionDaysEnabled) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Retention Period", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "${retentionDays.toInt()} days",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Slider(
                    value = retentionDays,
                    onValueChange = { retentionDays = it },
                    onValueChangeFinished = {
                        onEvent(SettingsEvent.RetentionDaysChanged(retentionDays.toInt()))
                    },
                    valueRange = 180f..1095f,
                    steps = 30,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Automatically delete data older than the retention period.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Button(
                onClick = { onEvent(SettingsEvent.ResyncHealthConnect) },
                enabled = !uiState.isResyncing,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (uiState.isResyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Resync Health Connect Data")
            }
            Text(
                text = "Clear all data from Health Connect and reload the last 60 days.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun SleepSettingsSection(
    uiState: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
) {
    var sleepGoalValue by remember(uiState.goalSleepHours) {
        mutableFloatStateOf(uiState.goalSleepHours)
    }

    Column {
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

        var consistencyWindow by remember(uiState.consistencyThresholdMinutes) {
            mutableFloatStateOf(uiState.consistencyThresholdMinutes.toFloat())
        }
        ThresholdSliderItem(
            label = "Consistency Window",
            value = consistencyWindow,
            onValueChange = { consistencyWindow = it },
            onValueChangeFinished = { onEvent(SettingsEvent.ConsistencyThresholdChanged(consistencyWindow.toInt())) },
            valueRange = 0f..90f,
            steps = 17,
            displayValue = "${consistencyWindow.toInt()} min",
            description = "±Grace period (in minutes) around your median bedtime and wake time before your score starts to drop. Default: 30 min.",
        )

        var evaluationPeriod by remember(uiState.consistencyEvaluationDays) {
            mutableFloatStateOf(uiState.consistencyEvaluationDays.toFloat())
        }
        ThresholdSliderItem(
            label = "Evaluation Period",
            value = evaluationPeriod,
            onValueChange = { evaluationPeriod = it },
            onValueChangeFinished = { onEvent(SettingsEvent.ConsistencyEvaluationDaysChanged(evaluationPeriod.toInt())) },
            valueRange = 3f..14f,
            steps = 10,
            displayValue = "${evaluationPeriod.toInt()} days",
            description = "Number of recent sleep sessions scored to compute your current consistency. Default: 7.",
        )

        var baselineWindow by remember(uiState.consistencyBaselineDays) {
            mutableFloatStateOf(uiState.consistencyBaselineDays.toFloat())
        }
        ThresholdSliderItem(
            label = "Baseline Window",
            value = baselineWindow,
            onValueChange = { baselineWindow = it },
            onValueChangeFinished = { onEvent(SettingsEvent.ConsistencyBaselineDaysChanged(baselineWindow.toInt())) },
            valueRange = 3f..30f,
            steps = 26,
            displayValue = "${baselineWindow.toInt()} sessions",
            description = "Number of past sleep sessions used to calculate your median bedtime anchor. Default: 14.",
        )
    }
}

@Composable
private fun ThresholdSettingsSection(
    uiState: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
) {
    Column {
        var hrvOptimal by remember(uiState.hrvOptimalThreshold) { mutableFloatStateOf(uiState.hrvOptimalThreshold) }
        ThresholdSliderItem(
            label = "HRV Optimal",
            value = hrvOptimal,
            onValueChange = { hrvOptimal = it },
            onValueChangeFinished = { onEvent(SettingsEvent.HrvOptimalThresholdChanged(hrvOptimal)) },
            valueRange = 1.0f..1.2f,
            description = "HRV ratio to baseline to be considered Optimal (e.g. 100-120%).",
        )

        var hrvWarning by remember(uiState.hrvWarningThreshold) { mutableFloatStateOf(uiState.hrvWarningThreshold) }
        ThresholdSliderItem(
            label = "HRV Warning",
            value = hrvWarning,
            onValueChange = { hrvWarning = it },
            onValueChangeFinished = { onEvent(SettingsEvent.HrvWarningThresholdChanged(hrvWarning)) },
            valueRange = 0.8f..1.0f,
            description = "HRV ratio to baseline to be considered Warning (e.g. 80-100%).",
        )
        Spacer(modifier = Modifier.height(8.dp))

        var rhrOptimal by remember(uiState.rhrOptimalThreshold) { mutableFloatStateOf(uiState.rhrOptimalThreshold) }
        ThresholdSliderItem(
            label = "RHR Optimal",
            value = rhrOptimal,
            onValueChange = { rhrOptimal = it },
            onValueChangeFinished = { onEvent(SettingsEvent.RhrOptimalThresholdChanged(rhrOptimal)) },
            valueRange = 0.8f..1.0f,
            description = "RHR ratio to baseline to be considered Optimal (e.g. 80-100%).",
        )

        var rhrWarning by remember(uiState.rhrWarningThreshold) { mutableFloatStateOf(uiState.rhrWarningThreshold) }
        ThresholdSliderItem(
            label = "RHR Warning",
            value = rhrWarning,
            onValueChange = { rhrWarning = it },
            onValueChangeFinished = { onEvent(SettingsEvent.RhrWarningThresholdChanged(rhrWarning)) },
            valueRange = 1.0f..1.2f,
            description = "RHR ratio to baseline to be considered Warning (e.g. 100-120%).",
        )
    }
}

@Composable
private fun HeartRateZoneSection(
    uiState: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
    expandState: SettingsExpandState,
    onExpandStateChange: (SettingsExpandState) -> Unit,
) {
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
                    expanded = expandState.genderExpanded,
                    onExpandedChange = { onExpandStateChange(expandState.copy(genderExpanded = it)) },
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
                z1MinP = uiState.zone1MinPercent,
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

@Composable
private fun ActivitySettingsSection(
    uiState: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
) {
    var stepGoal by remember(uiState.stepGoal) { mutableFloatStateOf(uiState.stepGoal.toFloat()) }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Daily Step Goal", style = MaterialTheme.typography.bodyMedium)
            MetricTooltip(description = "Target steps per day. Reaching this goal shows as Optimal on the dashboard.")
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "${stepGoal.roundToInt()} steps",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = stepGoal,
            onValueChange = { stepGoal = it },
            onValueChangeFinished = { onEvent(SettingsEvent.StepGoalChanged(stepGoal.roundToInt())) },
            valueRange = 1000f..30000f,
            steps = 57,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AdvancedSettingsSection(
    uiState: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
) {
    var hrvText by remember(uiState.hrvBaselineOverride) {
        mutableStateOf(uiState.hrvBaselineOverride?.toInt()?.toString() ?: "")
    }
    var rhrText by remember(uiState.rhrBaselineOverride) {
        mutableStateOf(uiState.rhrBaselineOverride?.toInt()?.toString() ?: "")
    }
    var beforeMinutesText by remember(uiState.restingHrBeforeMinutes) {
        mutableStateOf(uiState.restingHrBeforeMinutes.toString())
    }
    var afterMinutesText by remember(uiState.restingHrAfterMinutes) {
        mutableStateOf(uiState.restingHrAfterMinutes.toString())
    }

    Column {
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

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = beforeMinutesText,
            onValueChange = { value ->
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = afterMinutesText,
            onValueChange = { value ->
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))

        var paiScaling by remember(uiState.paiScalingFactor) { mutableFloatStateOf(uiState.paiScalingFactor) }
        ThresholdSliderItem(
            label = "PAI Scaling Factor",
            value = paiScaling,
            onValueChange = { paiScaling = it },
            onValueChangeFinished = { onEvent(SettingsEvent.PaiScalingFactorChanged(paiScaling)) },
            valueRange = 0.1f..0.3f,
            steps = 20, // (0.3 - 0.1) / 0.01 = 20
            displayValue = "%.2f".format(paiScaling),
            description = "Adjusts how quickly you earn PAI points. Default: 0.20.",
        )
    }
}

@Composable
private fun HeartRateZonesDisplay(
    maxHr: Int,
    z1MinP: Float,
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
            "Zone 1" to (z1MinP.toDouble()..z1p.toDouble()),
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
            "Zone Percentages",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )

        ZonePercentageSliderItem(
            label = "Zone 1 Min",
            value = uiState.zone1MinPercent,
            onValueChange = { onEvent(SettingsEvent.ZonePercentagesChanged(it, uiState.zone1MaxPercent, uiState.zone2MaxPercent, uiState.zone3MaxPercent, uiState.zone4MaxPercent)) },
            allowedRange = 0.30f..uiState.zone1MaxPercent - 0.01f
        )
        ZonePercentageSliderItem(
            label = "Zone 1 Max",
            value = uiState.zone1MaxPercent,
            onValueChange = { onEvent(SettingsEvent.ZonePercentagesChanged(uiState.zone1MinPercent, it, uiState.zone2MaxPercent, uiState.zone3MaxPercent, uiState.zone4MaxPercent)) },
            allowedRange = uiState.zone1MinPercent + 0.01f..uiState.zone2MaxPercent - 0.01f
        )
        ZonePercentageSliderItem(
            label = "Zone 2 Max",
            value = uiState.zone2MaxPercent,
            onValueChange = { onEvent(SettingsEvent.ZonePercentagesChanged(uiState.zone1MinPercent, uiState.zone1MaxPercent, it, uiState.zone3MaxPercent, uiState.zone4MaxPercent)) },
            allowedRange = uiState.zone1MaxPercent + 0.01f..uiState.zone3MaxPercent - 0.01f
        )
        ZonePercentageSliderItem(
            label = "Zone 3 Max",
            value = uiState.zone3MaxPercent,
            onValueChange = { onEvent(SettingsEvent.ZonePercentagesChanged(uiState.zone1MinPercent, uiState.zone1MaxPercent, uiState.zone2MaxPercent, uiState.zone3MaxPercent, it)) },
            allowedRange = uiState.zone2MaxPercent + 0.01f..uiState.zone4MaxPercent - 0.01f
        )
        ZonePercentageSliderItem(
            label = "Zone 4 Max",
            value = uiState.zone4MaxPercent,
            onValueChange = { onEvent(SettingsEvent.ZonePercentagesChanged(uiState.zone1MinPercent, uiState.zone1MaxPercent, uiState.zone2MaxPercent, uiState.zone3MaxPercent, it)) },
            allowedRange = uiState.zone3MaxPercent + 0.01f..0.99f
        )
    }
}

@Composable
private fun ZonePercentageSliderItem(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    allowedRange: ClosedFloatingPointRange<Float>,
    valueRange: ClosedFloatingPointRange<Float> = 0.30f..0.99f
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
            onValueChange = { onValueChange(it.coerceIn(allowedRange)) },
            valueRange = valueRange,
            steps = ((valueRange.endInclusive - valueRange.start) * 100).roundToInt() - 1,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GenderSelector(
    selectedGender: String?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onGenderSelected: (String?) -> Unit,
) {
    val genders = listOf("Male", "Female", "Other", "Prefer not to say")

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
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
            onDismissRequest = { onExpandedChange(false) },
        ) {
            genders.forEach { gender ->
                DropdownMenuItem(
                    text = { Text(gender) },
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
                }
            )
        }
    }
}

@Composable
private fun AppThemeItem(
    uiState: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
) {
    DropdownPreferenceItem(
        label = "App Theme",
        selectedDisplayValue = uiState.appTheme.displayName,
        options = AppTheme.entries,
        onOptionSelected = { onEvent(SettingsEvent.AppThemeChanged(it)) },
        optionLabel = { it.displayName },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun SyncPreferenceItem(
    uiState: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
) {
    DropdownPreferenceItem(
        label = "Foreground Sync",
        selectedDisplayValue = uiState.syncPreference.displayName,
        options = SyncPreference.entries,
        onOptionSelected = { onEvent(SettingsEvent.SyncPreferenceChanged(it)) },
        optionLabel = { it.displayName },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun SyncIntervalItem(
    uiState: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
) {
    DropdownPreferenceItem(
        label = "Sync Interval",
        selectedDisplayValue = "${uiState.syncIntervalHours}h",
        options = (1..24).toList(),
        onOptionSelected = { onEvent(SettingsEvent.SyncIntervalChanged(it)) },
        optionLabel = { "${it}h" },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun ThresholdSliderItem(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    description: String,
    steps: Int = ((valueRange.endInclusive - valueRange.start) * 100).roundToInt() - 1,
    displayValue: String = "${(value * 100).roundToInt()}%",
    onValueChangeFinished: (() -> Unit)? = null,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            MetricTooltip(description = description)
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = displayValue,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
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
    viewModel: SettingsViewModel,
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
                TextButton(onClick = { viewModel.signOut(context) }) {
                    Text("Sign Out")
                }
            } else {
                TextButton(onClick = { viewModel.signIn(context) }) {
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

@Composable
private fun BackupScheduleItem(
    uiState: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
) {
    DropdownPreferenceItem(
        label = "Auto Backup",
        selectedDisplayValue = uiState.backupSchedule.displayName,
        options = BackupSchedule.entries,
        onOptionSelected = { onEvent(SettingsEvent.BackupScheduleChanged(it)) },
        optionLabel = { it.displayName },
        modifier = Modifier.fillMaxWidth(),
    )
}
