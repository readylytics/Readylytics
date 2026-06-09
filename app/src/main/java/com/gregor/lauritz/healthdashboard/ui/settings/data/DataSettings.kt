package com.gregor.lauritz.healthdashboard.ui.settings.data

import androidx.annotation.StringRes
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
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gregor.lauritz.healthdashboard.R
import com.gregor.lauritz.healthdashboard.data.preferences.SyncPreference
import com.gregor.lauritz.healthdashboard.domain.model.HealthDataCategory
import com.gregor.lauritz.healthdashboard.domain.model.HealthDataType
import com.gregor.lauritz.healthdashboard.ui.components.DropdownPreferenceItem
import com.gregor.lauritz.healthdashboard.ui.components.SectionHeader
import com.gregor.lauritz.healthdashboard.ui.settings.SettingsEvent
import com.gregor.lauritz.healthdashboard.ui.settings.SyncSettingsState
import com.gregor.lauritz.healthdashboard.ui.settings.UIState
import com.gregor.lauritz.healthdashboard.ui.settings.common.SettingsConstants

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
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = {
                Text(
                    stringResource(R.string.settings_retention_enabled_label),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            trailingContent = {
                Switch(
                    checked = uiState.retentionDaysEnabled,
                    onCheckedChange = { onEvent(SettingsEvent.RetentionDaysEnabledChanged(it)) },
                )
            },
        )

        AnimatedVisibility(visible = uiState.retentionDaysEnabled) {
            Column(
                modifier =
                    Modifier.padding(
                        horizontal = SettingsConstants.HORIZONTAL_PADDING,
                        vertical = SettingsConstants.VERTICAL_SPACER_SMALL,
                    ),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.settings_retention_period_label),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text =
                            pluralStringResource(
                                R.plurals.settings_retention_days,
                                retentionDays.toInt(),
                                retentionDays.toInt(),
                            ),
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
                    text = stringResource(R.string.settings_retention_description),
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
                Text(stringResource(R.string.resync_button_label))
            }
            Text(
                text = stringResource(R.string.resync_button_description),
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
    val syncPrefLabels = SyncPreference.entries.associateWith { stringResource(it.labelRes()) }
    DropdownPreferenceItem(
        label = stringResource(R.string.settings_foreground_sync_label),
        selectedDisplayValue = stringResource(uiState.syncPreference.labelRes()),
        options = SyncPreference.entries,
        onOptionSelected = { onEvent(SettingsEvent.SyncPreferenceChanged(it)) },
        optionLabel = { syncPrefLabels[it] ?: it.name },
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
    val intervalLabels = (1..24).associateWith { stringResource(R.string.settings_sync_interval_display, it) }
    DropdownPreferenceItem(
        label = stringResource(R.string.settings_sync_interval_label),
        selectedDisplayValue = stringResource(R.string.settings_sync_interval_display, uiState.syncIntervalHours),
        options = (1..24).toList(),
        onOptionSelected = { onEvent(SettingsEvent.SyncIntervalChanged(it)) },
        optionLabel = { intervalLabels[it] ?: "${it}h" },
        modifier =
            Modifier.padding(
                horizontal = SettingsConstants.HORIZONTAL_PADDING,
                vertical = SettingsConstants.VERTICAL_SPACER_SMALL,
            ),
    )
}

@StringRes
private fun SyncPreference.labelRes(): Int =
    when (this) {
        SyncPreference.NEVER -> R.string.sync_preference_never
        SyncPreference.ALWAYS -> R.string.sync_preference_always
        SyncPreference.BY_TIME -> R.string.sync_preference_by_time
    }

/**
 * Lets the user pick the source device individually for each Health Connect data
 * type, grouped by category. "All devices" (the default) applies no source filter.
 */
@Composable
fun DataSourceSettingsSection(viewModel: DataSourceSettingsViewModel = hiltViewModel()) {
    val availableDevices by viewModel.availableDevices.collectAsStateWithLifecycle()
    val deviceByDataType by viewModel.deviceByDataType.collectAsStateWithLifecycle()
    val hasDevices = availableDevices.isNotEmpty()
    val allDevicesLabel = stringResource(R.string.data_sources_all_devices)
    val calibratingLabel = stringResource(R.string.data_sources_calibrating)
    val phoneLabel = stringResource(R.string.device_this_phone)
    // Include currently selected devices so a previously chosen but no-longer-detected
    // device (e.g. inactive within the discovery window) stays visible and re-selectable.
    val options =
        remember(availableDevices, deviceByDataType, allDevicesLabel) {
            (listOf(allDevicesLabel) + availableDevices + deviceByDataType.values).distinct()
        }
    val optionsDisplay =
        remember(options, phoneLabel) {
            options.associateWith { if (it == "This Phone") phoneLabel else it }
        }

    Column {
        Text(
            text = stringResource(R.string.data_sources_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier.padding(
                    horizontal = SettingsConstants.HORIZONTAL_PADDING,
                    vertical = SettingsConstants.VERTICAL_SPACER_SMALL,
                ),
        )

        HealthDataType.entries
            .groupBy { it.category }
            .forEach { (category, types) ->
                SectionHeader(stringResource(category.labelRes()))
                types.forEach { type ->
                    val selected = deviceByDataType[type.name]
                    DropdownPreferenceItem(
                        label = stringResource(type.labelRes()),
                        selectedDisplayValue =
                            when {
                                !hasDevices && selected == null -> calibratingLabel
                                selected != null -> {
                                    if (selected == "This Phone") phoneLabel else selected
                                }
                                else -> allDevicesLabel
                            },
                        options = options,
                        onOptionSelected = { choice ->
                            viewModel.updateDevice(
                                type = type,
                                deviceLabel = choice.takeIf { it != allDevicesLabel },
                            )
                        },
                        optionLabel = { optionsDisplay[it] ?: it },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = SettingsConstants.HORIZONTAL_PADDING,
                                    vertical = SettingsConstants.VERTICAL_SPACER_SMALL,
                                ),
                        enabled = hasDevices || selected != null,
                    )
                }
            }
    }
}

@StringRes
private fun HealthDataCategory.labelRes(): Int =
    when (this) {
        HealthDataCategory.ACTIVITY -> R.string.category_activity
        HealthDataCategory.BODY_MEASUREMENTS -> R.string.category_body_measurements
        HealthDataCategory.SLEEP -> R.string.category_sleep
        HealthDataCategory.VITALS -> R.string.category_vitals
    }

@StringRes
private fun HealthDataType.labelRes(): Int =
    when (this) {
        HealthDataType.EXERCISE -> R.string.data_type_exercise
        HealthDataType.STEPS -> R.string.data_type_steps
        HealthDataType.BODY_FAT -> R.string.data_type_body_fat
        HealthDataType.WEIGHT -> R.string.data_type_weight
        HealthDataType.SLEEP -> R.string.data_type_sleep
        HealthDataType.BLOOD_PRESSURE -> R.string.data_type_blood_pressure
        HealthDataType.HEART_RATE -> R.string.data_type_heart_rate
        HealthDataType.HRV -> R.string.data_type_hrv
        HealthDataType.OXYGEN_SATURATION -> R.string.data_type_oxygen_saturation
    }
