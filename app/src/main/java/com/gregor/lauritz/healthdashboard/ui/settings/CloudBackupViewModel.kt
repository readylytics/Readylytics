package com.gregor.lauritz.healthdashboard.ui.settings

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.MainActivity
import com.gregor.lauritz.healthdashboard.data.drive.DriveAuthManager
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.domain.backup.BackupUseCase
import com.gregor.lauritz.healthdashboard.domain.backup.RestoreUseCase
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
class CloudBackupViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val driveAuthManager: DriveAuthManager,
    private val backupUseCase: BackupUseCase,
    private val restoreUseCase: RestoreUseCase,
) : ViewModel() {

    private val transientState = MutableStateFlow(TransientBackupState())

    val uiState: StateFlow<CloudBackupState> = combine(
        settingsRepo.userPreferences,
        transientState
    ) { prefs, transient ->
        CloudBackupState(
            driveAccountEmail = prefs.driveAccountEmail,
            backupSchedule = prefs.backupSchedule,
            lastBackupTimestamp = prefs.lastBackupTimestamp,
            isBackingUp = transient.isBackingUp,
            isRestoring = transient.isRestoring,
            showRestoreConfirmDialog = transient.showRestoreConfirmDialog,
            driveError = transient.driveError,
            restoreSuccess = transient.restoreSuccess,
            pendingRestoreDir = transient.pendingRestoreDir,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = CloudBackupState()
    )

    fun onEvent(event: SettingsEvent, context: Context) {
        when (event) {
            SettingsEvent.BackupNow -> {
                viewModelScope.launch {
                    transientState.update { it.copy(isBackingUp = true, driveError = null) }
                    backupUseCase.execute()
                        .onFailure { e -> transientState.update { it.copy(driveError = e.message ?: "Backup failed") } }
                    transientState.update { it.copy(isBackingUp = false) }
                }
            }
            SettingsEvent.RestoreFromDrive -> {
                viewModelScope.launch {
                    transientState.update { it.copy(isRestoring = true, driveError = null) }
                    restoreUseCase.downloadAndValidate()
                        .onSuccess { dir ->
                            transientState.update {
                                it.copy(
                                    isRestoring = false,
                                    showRestoreConfirmDialog = true,
                                    pendingRestoreDir = dir,
                                )
                            }
                        }.onFailure { e ->
                            transientState.update {
                                it.copy(isRestoring = false, driveError = e.message ?: "Restore download failed")
                            }
                        }
                }
            }
            SettingsEvent.RestoreConfirmed -> {
                val dir = transientState.value.pendingRestoreDir ?: return
                transientState.update { it.copy(showRestoreConfirmDialog = false, isRestoring = true) }
                viewModelScope.launch {
                    when (val result = restoreUseCase.applyRestore(dir)) {
                        RestoreUseCase.RestoreResult.SuccessRequiresRestart -> {
                            val restartIntent = Intent(context, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            }
                            context.startActivity(restartIntent)
                        }
                        is RestoreUseCase.RestoreResult.Failure -> {
                            transientState.update { it.copy(isRestoring = false, driveError = result.cause.message) }
                        }
                        RestoreUseCase.RestoreResult.Success -> {
                            transientState.update { it.copy(isRestoring = false) }
                        }
                    }
                }
            }
            SettingsEvent.RestoreDismissed -> {
                transientState.value.pendingRestoreDir?.deleteRecursively()
                transientState.update { it.copy(showRestoreConfirmDialog = false, pendingRestoreDir = null) }
            }
            is SettingsEvent.BackupScheduleChanged ->
                viewModelScope.launch { settingsRepo.updateBackupSchedule(schedule = event.schedule) }
            SettingsEvent.DriveSignOut -> {
                viewModelScope.launch {
                    driveAuthManager.signOut(context = context)
                    settingsRepo.updateDriveAccountEmail(email = null)
                }
            }
            SettingsEvent.DriveSignIn -> {
                viewModelScope.launch {
                    driveAuthManager.signIn(activityContext = context).onFailure { e ->
                        transientState.update { it.copy(driveError = e.message ?: "Sign-in failed") }
                    }
                }
            }
            SettingsEvent.DismissDriveError -> {
                transientState.update { it.copy(driveError = null) }
            }
            else -> {}
        }
    }

    private data class TransientBackupState(
        val isBackingUp: Boolean = false,
        val isRestoring: Boolean = false,
        val showRestoreConfirmDialog: Boolean = false,
        val driveError: String? = null,
        val restoreSuccess: Boolean = false,
        val pendingRestoreDir: File? = null,
    )
}
