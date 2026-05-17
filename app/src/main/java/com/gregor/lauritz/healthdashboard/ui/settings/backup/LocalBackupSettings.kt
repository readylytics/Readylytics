package com.gregor.lauritz.healthdashboard.ui.settings.backup

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.data.preferences.BackupSchedule
import com.gregor.lauritz.healthdashboard.domain.backup.BackupFileInfo
import com.gregor.lauritz.healthdashboard.ui.components.DropdownPreferenceItem
import com.gregor.lauritz.healthdashboard.ui.settings.LocalBackupState
import com.gregor.lauritz.healthdashboard.ui.settings.SettingsEvent
import com.gregor.lauritz.healthdashboard.ui.settings.common.SettingsConstants
import java.text.DateFormat
import java.util.Date

@Composable
fun LocalBackupSection(
    uiState: LocalBackupState,
    onEvent: (SettingsEvent) -> Unit,
) {
    Column(
        modifier =
            Modifier.padding(
                horizontal = SettingsConstants.HORIZONTAL_PADDING,
                vertical = SettingsConstants.VERTICAL_SPACER_SMALL,
            ),
    ) {
        Text(
            text =
                "Backups are stored locally on your device. " +
                    "You can create a new backup at any time or restore from a previous one.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(SettingsConstants.VERTICAL_SPACER_LARGE))

        // Directory Picker
        BackupDirectoryItem(uiState = uiState, onEvent = onEvent)

        Spacer(modifier = Modifier.height(SettingsConstants.VERTICAL_SPACER_LARGE))

        // Create Backup Button
        Button(
            onClick = { onEvent(SettingsEvent.CreateLocalBackup) },
            enabled = !uiState.isBackingUp && !uiState.isRestoring,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (uiState.isBackingUp) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("Create New Backup")
            }
        }

        Spacer(modifier = Modifier.height(SettingsConstants.VERTICAL_SPACER_LARGE))

        // Backup schedule dropdown
        BackupScheduleItem(uiState = uiState, onEvent = onEvent)

        Spacer(modifier = Modifier.height(SettingsConstants.VERTICAL_SPACER_LARGE))

        Text(
            text = "Available Backups",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(SettingsConstants.VERTICAL_SPACER_SMALL))

        if (uiState.availableBackups.isEmpty()) {
            Text(
                text = "No backups found.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            uiState.availableBackups.forEach { file ->
                BackupFileItem(
                    file = file,
                    onRestore = { onEvent(SettingsEvent.RestoreLocalBackup(file)) },
                    onDelete = { onEvent(SettingsEvent.DeleteLocalBackup(file)) },
                    enabled = !uiState.isBackingUp && !uiState.isRestoring,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), thickness = 0.5.dp)
            }
        }

        // Inline error display
        if (uiState.backupError != null) {
            Spacer(modifier = Modifier.height(SettingsConstants.VERTICAL_SPACER))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = uiState.backupError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onEvent(SettingsEvent.DismissBackupError) }) {
                    Text("Dismiss")
                }
            }
        }
    }
}

@Composable
private fun BackupDirectoryItem(
    uiState: LocalBackupState,
    onEvent: (SettingsEvent) -> Unit,
) {
    val launcher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            uri?.let {
                // Use the URI string for persistence and access via SAF
                onEvent(SettingsEvent.ChangeBackupDirectory(it.toString()))
            }
        }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Backup Directory", style = MaterialTheme.typography.bodyMedium)
            val displayPath =
                if (uiState.backupDirectory.isNullOrBlank()) {
                    "Default (App Internal)"
                } else {
                    Uri.parse(uiState.backupDirectory).path ?: uiState.backupDirectory
                }
            Text(
                text = displayPath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = { launcher.launch(null) }) {
            Text("Change")
        }
    }
}

@Composable
private fun BackupFileItem(
    file: BackupFileInfo,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.History,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
        ) {
            Text(
                text =
                    DateFormat
                        .getDateTimeInstance(
                            DateFormat.MEDIUM,
                            DateFormat.SHORT,
                        ).format(Date(file.lastModified)),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "${file.sizeBytes / 1024} KB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onRestore, enabled = enabled) {
            Text("Restore")
        }
        IconButton(onClick = onDelete, enabled = enabled) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Delete backup",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun BackupScheduleItem(
    uiState: LocalBackupState,
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

private val BackupSchedule.displayName: String
    get() =
        when (this) {
            BackupSchedule.MANUAL -> "Manual only"
            BackupSchedule.DAILY -> "Daily"
            BackupSchedule.WEEKLY -> "Weekly"
        }
