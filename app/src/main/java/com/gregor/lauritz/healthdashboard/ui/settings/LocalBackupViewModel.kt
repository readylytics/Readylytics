package com.gregor.lauritz.healthdashboard.ui.settings

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.MainActivity
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.security.EncryptionManager
import com.gregor.lauritz.healthdashboard.domain.backup.BackupFileInfo
import com.gregor.lauritz.healthdashboard.domain.backup.LocalBackupManager
import com.gregor.lauritz.healthdashboard.domain.backup.LocalRestoreManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LocalBackupViewModel
    @Inject
    constructor(
        private val settingsRepo: SettingsRepository,
        private val localBackupManager: LocalBackupManager,
        private val localRestoreManager: LocalRestoreManager,
        private val encryptionManager: EncryptionManager,
    ) : ViewModel() {
        private val transientState = MutableStateFlow(TransientBackupState())

        val uiState: StateFlow<LocalBackupState> =
            combine(
                settingsRepo.userPreferences,
                transientState,
            ) { prefs, transient ->
                LocalBackupState(
                    lastBackupTimestamp = prefs.lastBackupTimestamp,
                    backupSchedule = prefs.backupSchedule,
                    backupDirectory = prefs.backupDirectoryUri,
                    isBackingUp = transient.isBackingUp,
                    isRestoring = transient.isRestoring,
                    showRestoreConfirmDialog = transient.showRestoreConfirmDialog,
                    backupError = transient.backupError,
                    restoreSuccess = transient.restoreSuccess,
                    pendingRestoreFile = transient.pendingRestoreFile,
                    availableBackups = localBackupManager.listBackups(),
                    passwordVerificationResult = transient.passwordVerificationResult,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = LocalBackupState(),
            )

        fun onEvent(
            event: SettingsEvent,
            context: Context,
        ) {
            when (event) {
                SettingsEvent.CreateLocalBackup -> {
                    viewModelScope.launch {
                        transientState.update { it.copy(isBackingUp = true, backupError = null) }
                        localBackupManager
                            .createBackup()
                            .onSuccess {
                                settingsRepo.updateLastBackupTimestamp(System.currentTimeMillis())
                            }.onFailure { e ->
                                transientState.update { it.copy(backupError = e.message ?: "Backup failed") }
                            }
                        transientState.update { it.copy(isBackingUp = false) }
                    }
                }
                is SettingsEvent.RestoreLocalBackup -> {
                    viewModelScope.launch {
                        transientState.update { it.copy(isRestoring = true, backupError = null) }
                        localRestoreManager
                            .validate(event.file.uri)
                            .onSuccess {
                                transientState.update {
                                    it.copy(
                                        isRestoring = false,
                                        showRestoreConfirmDialog = true,
                                        pendingRestoreFile = event.file,
                                    )
                                }
                            }.onFailure { e ->
                                transientState.update {
                                    it.copy(isRestoring = false, backupError = e.message ?: "Restore validation failed")
                                }
                            }
                    }
                }
                SettingsEvent.RestoreConfirmed -> {
                    val file = transientState.value.pendingRestoreFile ?: return
                    transientState.update { it.copy(showRestoreConfirmDialog = false, isRestoring = true) }
                    viewModelScope.launch {
                        when (val result = localRestoreManager.applyRestore(file.uri)) {
                            LocalRestoreManager.RestoreResult.SuccessRequiresRestart -> {
                                val restartIntent =
                                    Intent(context, MainActivity::class.java).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                    }
                                context.startActivity(restartIntent)
                            }
                            is LocalRestoreManager.RestoreResult.Failure -> {
                                transientState.update {
                                    it.copy(
                                        isRestoring = false,
                                        backupError = result.cause.message,
                                    )
                                }
                            }
                            LocalRestoreManager.RestoreResult.Success -> {
                                transientState.update { it.copy(isRestoring = false) }
                            }
                        }
                    }
                }
                SettingsEvent.RestoreDismissed -> {
                    transientState.update { it.copy(showRestoreConfirmDialog = false, pendingRestoreFile = null) }
                }
                is SettingsEvent.ChangeBackupDirectory -> {
                    viewModelScope.launch {
                        val uri = event.path.toUri()
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                        )
                        settingsRepo.updateBackupDirectoryUri(event.path)
                    }
                }
                is SettingsEvent.DeleteLocalBackup -> {
                    viewModelScope.launch {
                        // For SAF, deletion is via DocumentFile
                        // For internal, it's java.io.File
                        // Simplify by having LocalBackupManager handle it or just use ContentResolver
                        // For now, let's assume we can use DocumentFile
                        try {
                            androidx.documentfile.provider.DocumentFile
                                .fromSingleUri(context, event.file.uri)
                                ?.delete()
                        } catch (_: Exception) {
                            // ignore
                        }
                        // Trigger state refresh
                        transientState.update { it.copy() }
                    }
                }
                SettingsEvent.DismissBackupError -> {
                    transientState.update { it.copy(backupError = null) }
                }
                is SettingsEvent.UpdateBackupPassword -> {
                    viewModelScope.launch {
                        val hash = if (event.raw.isBlank()) null else encryptionManager.encrypt(event.raw)
                        settingsRepo.updateBackupPasswordHash(hash)
                    }
                }
                is SettingsEvent.VerifyBackupPassword -> {
                    viewModelScope.launch {
                        val currentHash = settingsRepo.userPreferences.first().backupPasswordHash
                        val matches =
                            if (currentHash == null) {
                                event.test.isEmpty()
                            } else {
                                try {
                                    encryptionManager.decrypt(currentHash) == event.test
                                } catch (_: Exception) {
                                    false
                                }
                            }
                        transientState.update { it.copy(passwordVerificationResult = matches) }
                    }
                }
                SettingsEvent.ClearPasswordVerificationResult -> {
                    transientState.update { it.copy(passwordVerificationResult = null) }
                }
                is SettingsEvent.BackupScheduleChanged ->
                    viewModelScope.launch { settingsRepo.updateBackupSchedule(schedule = event.schedule) }
                else -> {}
            }
        }

        private data class TransientBackupState(
            val isBackingUp: Boolean = false,
            val isRestoring: Boolean = false,
            val showRestoreConfirmDialog: Boolean = false,
            val backupError: String? = null,
            val restoreSuccess: Boolean = false,
            val pendingRestoreFile: BackupFileInfo? = null,
            val passwordVerificationResult: Boolean? = null,
        )
    }
