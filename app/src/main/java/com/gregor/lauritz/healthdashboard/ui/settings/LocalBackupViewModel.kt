package com.gregor.lauritz.healthdashboard.ui.settings

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.MainActivity
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.domain.backup.LocalBackupManager
import com.gregor.lauritz.healthdashboard.domain.backup.LocalRestoreManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class LocalBackupViewModel
    @Inject
    constructor(
        private val settingsRepo: SettingsRepository,
        private val localBackupManager: LocalBackupManager,
        private val localRestoreManager: LocalRestoreManager,
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
                    backupDirectory = localBackupManager.getBackupDirectory().absolutePath,
                    isBackingUp = transient.isBackingUp,
                    isRestoring = transient.isRestoring,
                    showRestoreConfirmDialog = transient.showRestoreConfirmDialog,
                    backupError = transient.backupError,
                    restoreSuccess = transient.restoreSuccess,
                    pendingRestoreFile = transient.pendingRestoreFile,
                    availableBackups = localBackupManager.listBackups(),
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
                            .validate(event.file)
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
                        val result = localRestoreManager.applyRestore(file)
                        when (result) {
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
                    localBackupManager.updateBackupDirectory(event.path)
                    // Trigger refresh
                    transientState.update { it.copy() }
                }
                is SettingsEvent.DeleteLocalBackup -> {
                    viewModelScope.launch {
                        event.file.delete()
                        // Trigger state refresh
                        transientState.update { it.copy() }
                    }
                }
                SettingsEvent.DismissBackupError -> {
                    transientState.update { it.copy(backupError = null) }
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
            val pendingRestoreFile: File? = null,
        )
    }
