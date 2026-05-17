package com.gregor.lauritz.healthdashboard.ui.settings.data

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gregor.lauritz.healthdashboard.data.preferences.SyncPreference
import com.gregor.lauritz.healthdashboard.ui.components.DropdownPreferenceItem
import com.gregor.lauritz.healthdashboard.ui.settings.SettingsEvent
import com.gregor.lauritz.healthdashboard.ui.settings.SyncSettingsState
import com.gregor.lauritz.healthdashboard.ui.settings.UIState
import com.gregor.lauritz.healthdashboard.ui.settings.common.SettingsConstants
import kotlinx.coroutines.launch

@Composable
fun SyncSettingsSection(
    uiState: SyncSettingsState,
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
fun DataManagementSection(
    uiState: UIState,
    isResyncing: Boolean,
    onEvent: (SettingsEvent) -> Unit,
    onSyncEvent: (SettingsEvent) -> Unit,
) {
    var retentionDays by remember(uiState.retentionDays) {
        mutableFloatStateOf(uiState.retentionDays.toFloat())
    }

    Column {
        Column(
            modifier =
                Modifier.padding(
                    horizontal = SettingsConstants.HORIZONTAL_PADDING,
                    vertical = SettingsConstants.VERTICAL_SPACER_LARGE,
                ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Retention Enabled", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.weight(1f))
                Switch(
                    checked = uiState.retentionDaysEnabled,
                    onCheckedChange = { onEvent(SettingsEvent.RetentionDaysEnabledChanged(it)) },
                )
            }
        }

        AnimatedVisibility(visible = uiState.retentionDaysEnabled) {
            Column(
                modifier =
                    Modifier.padding(
                        horizontal = SettingsConstants.HORIZONTAL_PADDING,
                        vertical = SettingsConstants.VERTICAL_SPACER_SMALL,
                    ),
            ) {
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
                    modifier = Modifier.padding(top = SettingsConstants.VERTICAL_SPACER_SMALL),
                )
            }
        }

        Column(
            modifier =
                Modifier.padding(
                    horizontal = SettingsConstants.HORIZONTAL_PADDING,
                    vertical = SettingsConstants.VERTICAL_SPACER_LARGE,
                ),
        ) {
            Button(
                onClick = { onSyncEvent(SettingsEvent.ResyncHealthConnect) },
                enabled = !isResyncing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isResyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(SettingsConstants.VERTICAL_SPACER))
                }
                Text("Resync Health Connect Data")
            }
            Text(
                text = "Clear all data from Health Connect and reload the last 60 days.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = SettingsConstants.VERTICAL_SPACER_SMALL),
            )
        }
    }
}

@Composable
private fun SyncPreferenceItem(
    uiState: SyncSettingsState,
    onEvent: (SettingsEvent) -> Unit,
) {
    DropdownPreferenceItem(
        label = "Foreground Sync",
        selectedDisplayValue = uiState.syncPreference.displayName,
        options = SyncPreference.entries,
        onOptionSelected = { onEvent(SettingsEvent.SyncPreferenceChanged(it)) },
        optionLabel = { it.displayName },
        modifier =
            Modifier.padding(
                horizontal = SettingsConstants.HORIZONTAL_PADDING,
                vertical = SettingsConstants.VERTICAL_SPACER_SMALL,
            ),
    )
}

@Composable
private fun SyncIntervalItem(
    uiState: SyncSettingsState,
    onEvent: (SettingsEvent) -> Unit,
) {
    DropdownPreferenceItem(
        label = "Sync Interval",
        selectedDisplayValue = "${uiState.syncIntervalHours}h",
        options = (1..24).toList(),
        onOptionSelected = { onEvent(SettingsEvent.SyncIntervalChanged(it)) },
        optionLabel = { "${it}h" },
        modifier =
            Modifier.padding(
                horizontal = SettingsConstants.HORIZONTAL_PADDING,
                vertical = SettingsConstants.VERTICAL_SPACER_SMALL,
            ),
    )
}

private val SyncPreference.displayName: String
    get() =
        when (this) {
            SyncPreference.NEVER -> "Never"
            SyncPreference.ALWAYS -> "Always"
            SyncPreference.BY_TIME -> "By Time"
        }

@Composable
fun DeviceSelectionSection(viewModel: DeviceSettingsViewModel = hiltViewModel()) {
    val availableDevices by viewModel.availableDevices.collectAsStateWithLifecycle()
    val primaryDevice by viewModel.primaryDevice.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    val hasDevices = availableDevices.isNotEmpty()

    Column(
        modifier =
            Modifier.padding(
                horizontal = SettingsConstants.HORIZONTAL_PADDING,
                vertical = SettingsConstants.VERTICAL_SPACER_SMALL,
            ),
    ) {
        DropdownPreferenceItem(
            label = "Primary Device",
            selectedDisplayValue =
                when {
                    !hasDevices -> "Calibrating..."
                    primaryDevice != null -> primaryDevice!!
                    else -> "Select a device"
                },
            options = availableDevices,
            onOptionSelected = { deviceName ->
                coroutineScope.launch { viewModel.updatePrimaryDevice(deviceName) }
            },
            optionLabel = { it },
            modifier = Modifier.fillMaxWidth(),
            enabled = hasDevices,
        )
        Text(
            text = "Device used for metrics calculation",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp),
        )
    }
}
