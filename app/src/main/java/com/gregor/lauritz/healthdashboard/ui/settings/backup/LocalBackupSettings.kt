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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
    if (uiState.showSetPasswordDialog) {
        SetPasswordDialog(
            onDismiss = { onEvent(SettingsEvent.DismissSetPasswordDialog) },
            onConfirm = { password ->
                onEvent(
                    SettingsEvent.UpdateBackupPassword(
                        password,
                        autoStartBackup = !uiState.isPasswordSet,
                    ),
                )
            },
        )
    }

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

        // Password Section
        BackupPasswordSection(uiState = uiState, onEvent = onEvent)

        Spacer(modifier = Modifier.height(SettingsConstants.VERTICAL_SPACER_LARGE))

        // Create Backup Button
        Button(
            onClick = { onEvent(SettingsEvent.CreateLocalBackup) },
            enabled = !uiState.isBackingUp && !uiState.isRestoring && !uiState.isReencrypting,
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

        if (uiState.isReencrypting) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    text = "Updating existing backup passwords...",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 8.dp),
                )
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
                    enabled = !uiState.isBackingUp && !uiState.isRestoring && !uiState.isReencrypting,
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
private fun BackupPasswordSection(
    uiState: LocalBackupState,
    onEvent: (SettingsEvent) -> Unit,
) {
    var testPassword by remember { mutableStateOf("") }
    var showTestPassword by remember { mutableStateOf(false) }

    Column {
        Text(
            text = "Security",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(SettingsConstants.VERTICAL_SPACER_SMALL))

        // Change Password Row
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Backup Password", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = if (uiState.isPasswordSet) "Encryption is enabled." else "Encryption is disabled.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { onEvent(SettingsEvent.OpenSetPasswordDialog) }) {
                Text(if (uiState.isPasswordSet) "Change" else "Set")
            }
        }

        Spacer(modifier = Modifier.height(SettingsConstants.VERTICAL_SPACER_LARGE))

        // Test Password
        OutlinedTextField(
            value = testPassword,
            onValueChange = {
                testPassword = it
                onEvent(SettingsEvent.ClearPasswordVerificationResult)
            },
            label = { Text("Test Backup Password") },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (showTestPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
            trailingIcon = {
                IconButton(onClick = { showTestPassword = !showTestPassword }) {
                    Icon(
                        imageVector = if (showTestPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (showTestPassword) "Hide password" else "Show password",
                    )
                }
            },
            singleLine = true,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val result = uiState.passwordVerificationResult
            if (result != null) {
                Text(
                    text = if (result) "Match! ✓" else "No match ✗",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (result) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            TextButton(
                onClick = { onEvent(SettingsEvent.VerifyBackupPassword(testPassword)) },
                enabled = testPassword.isNotEmpty(),
            ) {
                Text("Verify")
            }
        }
    }
}

@Composable
private fun SetPasswordDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var repeatPassword by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set Backup Password") },
        text = {
            Column {
                Text(
                    "This password will be used to encrypt your backups with AES-256. " +
                        "If you lose it, you won't be able to restore your data.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("New Password") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation =
                        if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Next,
                        ),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                imageVector =
                                    if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showPassword) "Hide password" else "Show password",
                            )
                        }
                    },
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = repeatPassword,
                    onValueChange = { repeatPassword = it },
                    label = { Text("Repeat Password") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation =
                        if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Done,
                        ),
                    singleLine = true,
                    isError = password != repeatPassword && repeatPassword.isNotEmpty(),
                )
                if (password != repeatPassword && repeatPassword.isNotEmpty()) {
                    Text(
                        text = "Passwords do not match",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(password) },
                enabled = password.isNotEmpty() && password == repeatPassword,
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
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
    val backupDate =
        DateFormat
            .getDateTimeInstance(
                DateFormat.MEDIUM,
                DateFormat.SHORT,
            ).format(Date(file.lastModified))

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
                text = backupDate,
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
        IconButton(
            onClick = onDelete,
            enabled = enabled,
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Delete backup from $backupDate",
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
