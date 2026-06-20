package app.readylytics.health.ui.settings.data

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readylytics.health.R
import app.readylytics.health.data.preferences.BackgroundSyncInterval
import app.readylytics.health.data.preferences.SyncPreference
import app.readylytics.health.domain.model.HealthDataCategory
import app.readylytics.health.domain.model.HealthDataType
import app.readylytics.health.ui.components.DropdownPreferenceItem
import app.readylytics.health.ui.components.SectionHeader
import app.readylytics.health.ui.components.SettingsToggleItem
import app.readylytics.health.ui.settings.SettingsEvent
import app.readylytics.health.ui.settings.SyncSettingsState
import app.readylytics.health.ui.settings.UIState
import app.readylytics.health.ui.settings.common.SettingsConstants

@Composable
fun SyncSettingsSection(
    uiState: SyncSettingsState,
    onEvent: (SettingsEvent) -> Unit,
) {
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    val backgroundPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = PermissionController.createRequestPermissionResultContract(),
        ) { granted ->
            if (granted.contains(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)) {
                onEvent(SettingsEvent.BackgroundSyncToggled(true))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }

    Column {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = {
                Text(
                    stringResource(R.string.sync_on_app_open_label),
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            trailingContent = {
                Switch(
                    checked = uiState.syncPreference != SyncPreference.NEVER,
                    onCheckedChange = {
                        onEvent(
                            SettingsEvent.SyncPreferenceChanged(
                                if (it) SyncPreference.ALWAYS else SyncPreference.NEVER,
                            ),
                        )
                    },
                )
            },
        )

        SettingsToggleItem(
            label = stringResource(R.string.background_sync_label),
            description = stringResource(R.string.background_sync_description),
            checked = uiState.backgroundSyncEnabled,
            onCheckedChange = { enabled ->
                if (enabled) {
                    backgroundPermissionLauncher.launch(
                        setOf(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND),
                    )
                } else {
                    onEvent(SettingsEvent.BackgroundSyncToggled(false))
                }
            },
        )

        AnimatedVisibility(visible = uiState.backgroundSyncEnabled) {
            BackgroundSyncIntervalItem(uiState = uiState, onEvent = onEvent)
        }
    }
}

@Composable
private fun BackgroundSyncIntervalItem(
    uiState: SyncSettingsState,
    onEvent: (SettingsEvent) -> Unit,
) {
    val intervalLabels =
        BackgroundSyncInterval.entries.associateWith { stringResource(it.labelRes()) }
    val selected = BackgroundSyncInterval.fromMinutes(uiState.backgroundSyncIntervalMinutes)
    DropdownPreferenceItem(
        label = stringResource(R.string.background_sync_interval_label),
        selectedDisplayValue = intervalLabels[selected] ?: selected.name,
        options = BackgroundSyncInterval.entries,
        onOptionSelected = { onEvent(SettingsEvent.BackgroundSyncIntervalChanged(it.minutes)) },
        optionLabel = { intervalLabels[it] ?: it.name },
        modifier =
            Modifier.padding(
                horizontal = SettingsConstants.HORIZONTAL_PADDING,
                vertical = SettingsConstants.VERTICAL_SPACER_SMALL,
            ),
    )
}

@StringRes
private fun BackgroundSyncInterval.labelRes(): Int =
    when (this) {
        BackgroundSyncInterval.MINUTES_15 -> R.string.background_sync_interval_15m
        BackgroundSyncInterval.HOUR_1 -> R.string.background_sync_interval_1h
        BackgroundSyncInterval.HOURS_4 -> R.string.background_sync_interval_4h
        BackgroundSyncInterval.HOURS_12 -> R.string.background_sync_interval_12h
        BackgroundSyncInterval.DAILY -> R.string.background_sync_interval_daily
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

/**
 * Lets the user pick the source device individually for each Health Connect data
 * type, grouped by category. "All devices" (the default) applies no source filter.
 */
@Composable
fun DataSourceSettingsSection(viewModel: DataSourceSettingsViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val availableDevices = uiState.availableDevices
    val deviceByDataType = uiState.deviceByDataType
    val hasDevices = availableDevices.isNotEmpty()
    val allDevicesLabel = stringResource(R.string.data_sources_all_devices)
    val calibratingLabel = stringResource(R.string.data_sources_calibrating)
    val phoneLabel = stringResource(R.string.device_this_phone)
    val googleFitLabel = stringResource(R.string.provider_google_fit)
    val samsungHealthWatchLabel = stringResource(R.string.provider_samsung_health_watch)
    val samsungHealthPhoneLabel = stringResource(R.string.provider_samsung_health_phone)
    val garminConnectLabel = stringResource(R.string.provider_garmin_connect)
    val whoopLabel = stringResource(R.string.provider_whoop)
    val ouraLabel = stringResource(R.string.provider_oura)
    val stravaLabel = stringResource(R.string.provider_strava)
    val withingsLabel = stringResource(R.string.provider_withings)

    // Include currently selected devices so a previously chosen but no-longer-detected
    // device (e.g. inactive within the discovery window) stays visible and re-selectable.
    val options =
        remember(availableDevices, deviceByDataType, allDevicesLabel) {
            (listOf(allDevicesLabel) + availableDevices + deviceByDataType.values).distinct()
        }
    val optionsDisplay =
        remember(
            options,
            phoneLabel,
            googleFitLabel,
            samsungHealthWatchLabel,
            samsungHealthPhoneLabel,
            garminConnectLabel,
            whoopLabel,
            ouraLabel,
            stravaLabel,
            withingsLabel,
        ) {
            options.associateWith {
                when (it) {
                    "This Phone" -> phoneLabel
                    "Google Fit" -> googleFitLabel
                    "Samsung Health (Watch)" -> samsungHealthWatchLabel
                    "Samsung Health (Phone)" -> samsungHealthPhoneLabel
                    "Garmin Connect" -> garminConnectLabel
                    "Whoop" -> whoopLabel
                    "Oura" -> ouraLabel
                    "Strava" -> stravaLabel
                    "Withings" -> withingsLabel
                    else -> it
                }
            }
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
                                    optionsDisplay[selected] ?: selected
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

        Column(
            modifier =
                Modifier.padding(
                    horizontal = SettingsConstants.HORIZONTAL_PADDING,
                    vertical = SettingsConstants.VERTICAL_SPACER_LARGE,
                ),
        ) {
            Button(
                onClick = { viewModel.onApply() },
                enabled = uiState.hasPendingChanges && !uiState.isResyncing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (uiState.isResyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(SettingsConstants.VERTICAL_SPACER))
                }
                Text(stringResource(R.string.data_sources_apply_button))
            }
            Text(
                text = stringResource(R.string.data_sources_apply_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = SettingsConstants.VERTICAL_SPACER_SMALL),
            )
        }
    }

    if (uiState.showDeviceChangeNotice) {
        DeviceChangeNoticeDialog(onAcknowledged = viewModel::onNoticeAcknowledged)
    }
}

@Composable
private fun DeviceChangeNoticeDialog(onAcknowledged: (dismissPermanently: Boolean) -> Unit) {
    var dontShowAgain by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { onAcknowledged(dontShowAgain) },
        title = { Text(stringResource(R.string.device_change_notice_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.device_change_notice_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .toggleable(
                                value = dontShowAgain,
                                role = Role.Checkbox,
                                onValueChange = { dontShowAgain = it },
                            ).padding(top = SettingsConstants.VERTICAL_SPACER),
                ) {
                    Checkbox(checked = dontShowAgain, onCheckedChange = null)
                    Spacer(modifier = Modifier.width(SettingsConstants.VERTICAL_SPACER_SMALL))
                    Text(
                        text = stringResource(R.string.device_change_notice_dont_show_again),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAcknowledged(dontShowAgain) }) {
                Text(stringResource(R.string.device_change_notice_confirm))
            }
        },
    )
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
