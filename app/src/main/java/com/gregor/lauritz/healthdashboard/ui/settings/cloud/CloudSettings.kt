package com.gregor.lauritz.healthdashboard.ui.settings.cloud

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.data.preferences.BackupSchedule
import com.gregor.lauritz.healthdashboard.data.preferences.SyncPreference
import com.gregor.lauritz.healthdashboard.ui.components.DropdownPreferenceItem
import com.gregor.lauritz.healthdashboard.ui.settings.CloudBackupState
import com.gregor.lauritz.healthdashboard.ui.settings.SettingsEvent
import com.gregor.lauritz.healthdashboard.ui.settings.SyncSettingsState
import com.gregor.lauritz.healthdashboard.ui.settings.UIState
import com.gregor.lauritz.healthdashboard.ui.settings.common.SettingsConstants
import java.text.DateFormat
import java.util.Date

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
                enabled = !uiState.isResyncing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (uiState.isResyncing) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudBackupSection(
    uiState: CloudBackupState,
    onEvent: (SettingsEvent) -> Unit,
) {
    val signedIn = uiState.driveAccountEmail != null

    Column(
        modifier =
            Modifier.padding(
                horizontal = SettingsConstants.HORIZONTAL_PADDING,
                vertical = SettingsConstants.VERTICAL_SPACER_SMALL,
            ),
    ) {
        // Account row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Google Account", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = uiState.driveAccountEmail ?: "Not connected",
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
                TextButton(onClick = { onEvent(SettingsEvent.DriveSignOut) }) {
                    Text("Sign Out")
                }
            } else {
                TextButton(onClick = { onEvent(SettingsEvent.DriveSignIn) }) {
                    Text("Sign In")
                }
            }
        }

        Spacer(modifier = Modifier.height(SettingsConstants.VERTICAL_SPACER))

        // Backup schedule dropdown
        BackupScheduleItem(uiState = uiState, onEvent = onEvent)

        Spacer(modifier = Modifier.height(SettingsConstants.VERTICAL_SPACER))

        // Last backup timestamp
        // Note: DateFormat.getDateTimeInstance uses device locale for formatting.
        // TODO: Add locale-specific tests to verify formatting across different device locales.
        val lastBackupText =
            if (uiState.lastBackupTimestamp == 0L) {
                "Never backed up"
            } else {
                "Last backup: ${DateFormat.getDateTimeInstance(
                    DateFormat.SHORT,
                    DateFormat.SHORT,
                ).format(Date(uiState.lastBackupTimestamp))}"
            }
        Text(
            text = lastBackupText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(SettingsConstants.VERTICAL_SPACER_LARGE))

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
            Spacer(modifier = Modifier.size(SettingsConstants.VERTICAL_SPACER))
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
            Spacer(modifier = Modifier.height(SettingsConstants.VERTICAL_SPACER))
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
    uiState: CloudBackupState,
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

private val BackupSchedule.displayName: String
    get() =
        when (this) {
            BackupSchedule.MANUAL -> "Manual only"
            BackupSchedule.DAILY -> "Daily"
            BackupSchedule.WEEKLY -> "Weekly"
        }
