package app.readylytics.health.feature.settings.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
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
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.common.resolveOrNull
import app.readylytics.health.core.ui.components.DropdownPreferenceItem
import app.readylytics.health.data.preferences.BackupSchedule
import app.readylytics.health.domain.backup.BackupFileInfo
import app.readylytics.health.feature.settings.LocalBackupState
import app.readylytics.health.feature.settings.R
import app.readylytics.health.feature.settings.SettingsEvent
import java.text.DateFormat
import java.util.Date

@Composable
fun LocalBackupSection(
    uiState: LocalBackupState,
    onEvent: (SettingsEvent) -> Unit,
) {
    val resolvedBackupError = uiState.backupError.resolveOrNull()

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
                vertical = MaterialTheme.spacing.extraSmall,
            ),
    ) {
        Text(
            text = stringResource(R.string.backup_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium),
        )

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.smallMedium))

        BackupDirectoryItem(uiState = uiState, onEvent = onEvent)

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.smallMedium))

        BackupPasswordSection(uiState = uiState, onEvent = onEvent)

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.smallMedium))

        Button(
            onClick = { onEvent(SettingsEvent.CreateLocalBackup) },
            enabled = !uiState.isBackingUp && !uiState.isRestoring && !uiState.isReencrypting,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.spacing.medium),
        ) {
            if (uiState.isBackingUp) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(stringResource(R.string.backup_create_button))
            }
        }

        if (uiState.isReencrypting) {
            Spacer(modifier = Modifier.height(MaterialTheme.spacing.small))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(R.string.backup_reencrypting_message),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = MaterialTheme.spacing.small),
                )
            }
        }

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.smallMedium))

        BackupScheduleItem(uiState = uiState, onEvent = onEvent)

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.smallMedium))

        Text(
            text = stringResource(R.string.backup_section_available),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium),
        )

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.extraSmall))

        if (uiState.availableBackups.isEmpty()) {
            Text(
                text = stringResource(R.string.backup_none_found),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier.padding(
                        horizontal = MaterialTheme.spacing.medium,
                        vertical = MaterialTheme.spacing.small,
                    ),
            )
        } else {
            uiState.availableBackups.forEach { file ->
                BackupFileItem(
                    file = file,
                    onRestore = { onEvent(SettingsEvent.RestoreLocalBackup(file)) },
                    onDelete = { onEvent(SettingsEvent.DeleteLocalBackup(file)) },
                    enabled = !uiState.isBackingUp && !uiState.isRestoring && !uiState.isReencrypting,
                )
                HorizontalDivider(
                    modifier =
                        Modifier.padding(
                            horizontal = MaterialTheme.spacing.medium,
                            vertical = MaterialTheme.spacing.extraSmall,
                        ),
                    thickness = 0.5.dp,
                )
            }
        }

        if (resolvedBackupError != null) {
            Spacer(modifier = Modifier.height(MaterialTheme.spacing.small))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium),
            ) {
                Text(
                    text = resolvedBackupError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onEvent(SettingsEvent.DismissBackupError) }) {
                    Text(stringResource(R.string.action_dismiss))
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

    Column(
        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium),
    ) {
        Text(
            text = stringResource(R.string.backup_security_section),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(MaterialTheme.spacing.extraSmall))

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = MaterialTheme.spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.backup_password_label), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text =
                        if (uiState.isPasswordSet) {
                            stringResource(R.string.backup_encryption_enabled)
                        } else {
                            stringResource(R.string.backup_encryption_disabled)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { onEvent(SettingsEvent.OpenSetPasswordDialog) }) {
                Text(
                    stringResource(
                        if (uiState.isPasswordSet) R.string.action_change else R.string.action_set,
                    ),
                )
            }
        }

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.smallMedium))

        OutlinedTextField(
            value = testPassword,
            onValueChange = {
                testPassword = it
                onEvent(SettingsEvent.ClearPasswordVerificationResult)
            },
            label = { Text(stringResource(R.string.backup_test_password_label)) },
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
                        contentDescription =
                            stringResource(
                                if (showTestPassword) {
                                    R.string.accessibility_password_hide
                                } else {
                                    R.string.accessibility_password_show
                                },
                            ),
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
                    text =
                        stringResource(
                            if (result) R.string.backup_password_match else R.string.backup_password_no_match,
                        ),
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
                Text(stringResource(R.string.action_verify))
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
        title = { Text(stringResource(R.string.dialog_set_password_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.dialog_set_password_body),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = MaterialTheme.spacing.medium),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.backup_new_password_label)) },
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
                                contentDescription =
                                    stringResource(
                                        if (showPassword) {
                                            R.string.accessibility_password_hide
                                        } else {
                                            R.string.accessibility_password_show
                                        },
                                    ),
                            )
                        }
                    },
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(MaterialTheme.spacing.small))
                OutlinedTextField(
                    value = repeatPassword,
                    onValueChange = { repeatPassword = it },
                    label = { Text(stringResource(R.string.backup_repeat_password_label)) },
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
                        text = stringResource(R.string.error_passwords_do_not_match),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = MaterialTheme.spacing.extraSmall),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(password) },
                enabled = password.isNotEmpty() && password == repeatPassword,
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(app.readylytics.health.core.ui.R.string.action_cancel))
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
                onEvent(SettingsEvent.ChangeBackupDirectory(it.toString()))
            }
        }

    val displayPath =
        if (uiState.backupDirectory.isNullOrBlank()) {
            stringResource(R.string.backup_directory_default)
        } else {
            uiState.backupDirectory.toUri().path ?: uiState.backupDirectory
        }

    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = {
            Text(
                text = stringResource(R.string.backup_directory_label),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        supportingContent = {
            Text(
                text = displayPath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            TextButton(onClick = { launcher.launch(null) }) {
                Text(stringResource(R.string.action_change))
            }
        },
    )
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

    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Icon(
                imageVector = Icons.Default.History,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        headlineContent = {
            Text(
                text = backupDate,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        supportingContent = {
            Text(
                text = stringResource(R.string.backup_size_kb, file.sizeBytes / 1024),
                style = MaterialTheme.typography.bodySmall,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onRestore, enabled = enabled) {
                    Text(stringResource(R.string.action_restore))
                }
                IconButton(
                    onClick = onDelete,
                    enabled = enabled,
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.accessibility_backup_delete, backupDate),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
    )
}

@Composable
private fun BackupScheduleItem(
    uiState: LocalBackupState,
    onEvent: (SettingsEvent) -> Unit,
) {
    val scheduleLabels = BackupSchedule.entries.associateWith { stringResource(it.labelRes()) }
    DropdownPreferenceItem(
        label = stringResource(R.string.backup_auto_label),
        selectedDisplayValue = stringResource(uiState.backupSchedule.labelRes()),
        options = BackupSchedule.entries,
        onOptionSelected = { onEvent(SettingsEvent.BackupScheduleChanged(it)) },
        optionLabel = { scheduleLabels[it] ?: it.name },
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.medium),
    )
}

@StringRes
private fun BackupSchedule.labelRes(): Int =
    when (this) {
        BackupSchedule.MANUAL -> R.string.backup_schedule_manual
        BackupSchedule.DAILY -> R.string.backup_schedule_daily
        BackupSchedule.WEEKLY -> R.string.backup_schedule_weekly
    }
