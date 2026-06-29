package app.readylytics.health.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.readylytics.health.core.ui.common.UiText
import app.readylytics.health.di.IoDispatcher
import app.readylytics.health.domain.backup.BackupFileInfo
import app.readylytics.health.domain.backup.BackupService
import app.readylytics.health.domain.backup.RestoreResult
import app.readylytics.health.domain.backup.RestoreService
import app.readylytics.health.domain.backup.WrongBackupPasswordException
import app.readylytics.health.domain.preferences.BackupSettings
import app.readylytics.health.domain.preferences.UserPreferencesReader
import app.readylytics.health.domain.security.EncryptionManager
import app.readylytics.health.domain.util.logE
import app.readylytics.health.feature.settings.R
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LocalBackupViewModel
    @Inject
    constructor(
        private val settingsRepo: UserPreferencesReader,
        private val backupSettings: BackupSettings,
        private val backupService: BackupService,
        private val restoreService: RestoreService,
        private val encryptionManager: EncryptionManager,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        sealed interface SideEffect {
            data object RestartApp : SideEffect

            data class TakePersistableUriPermission(
                val uri: String,
            ) : SideEffect
        }

        private val _sideEffect = MutableSharedFlow<SideEffect>()
        val sideEffect: SharedFlow<SideEffect> = _sideEffect.asSharedFlow()

        private val transientState = MutableStateFlow(TransientBackupState())

        private val availableBackupsFlow: StateFlow<List<BackupFileInfo>> =
            transientState
                .map { it.refreshTrigger }
                .distinctUntilChanged()
                .flatMapLatest {
                    flow { emit(backupService.listBackups()) }
                        .flowOn(ioDispatcher)
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5000),
                    initialValue = emptyList(),
                )

        val uiState: StateFlow<LocalBackupState> =
            combine(
                settingsRepo.userPreferences,
                transientState,
                availableBackupsFlow,
            ) { prefs, transient, backups ->
                LocalBackupState(
                    lastBackupTimestamp = prefs.lastBackupTimestamp,
                    backupSchedule = prefs.backupSchedule,
                    backupDirectory = prefs.backupDirectoryUri,
                    isBackingUp = transient.isBackingUp,
                    isRestoring = transient.isRestoring,
                    isReencrypting = transient.isReencrypting,
                    isPasswordSet = prefs.backupPasswordHash != null,
                    showSetPasswordDialog = transient.showSetPasswordDialog,
                    showRestoreConfirmDialog = transient.showRestoreConfirmDialog,
                    backupError = transient.backupError,
                    restoreSuccess = transient.restoreSuccess,
                    pendingRestoreFile = transient.pendingRestoreFile,
                    availableBackups = backups,
                    passwordVerificationResult = transient.passwordVerificationResult,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = LocalBackupState(),
            )

        fun onEvent(event: SettingsEvent) {
            when (event) {
                SettingsEvent.CreateLocalBackup -> {
                    viewModelScope.launch {
                        val currentHash = settingsRepo.userPreferences.first().backupPasswordHash
                        if (currentHash == null) {
                            transientState.update { it.copy(showSetPasswordDialog = true) }
                        } else {
                            startBackup()
                        }
                    }
                }
                is SettingsEvent.RestoreLocalBackup -> {
                    viewModelScope.launch {
                        transientState.update { it.copy(isRestoring = true, backupError = null) }
                        restoreService
                            .validate(event.file.location)
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
                                    it.copy(
                                        isRestoring = false,
                                        backupError =
                                            e.toBackupError(
                                                defaultRes = R.string.error_backup_restore_validation,
                                            ),
                                    )
                                }
                            }
                    }
                }
                SettingsEvent.RestoreConfirmed -> {
                    val file = transientState.value.pendingRestoreFile ?: return
                    transientState.update { it.copy(showRestoreConfirmDialog = false, isRestoring = true) }
                    viewModelScope.launch {
                        when (val result = restoreService.applyRestore(file.location)) {
                            RestoreResult.SuccessRequiresRestart -> {
                                _sideEffect.emit(SideEffect.RestartApp)
                            }
                            is RestoreResult.PartialSuccessRequiresRestart -> {
                                transientState.update {
                                    it.copy(
                                        isRestoring = false,
                                        backupError = UiText.StringRes(R.string.restore_partial_success_message),
                                    )
                                }
                                _sideEffect.emit(SideEffect.RestartApp)
                            }
                            is RestoreResult.Failure -> {
                                transientState.update {
                                    it.copy(
                                        isRestoring = false,
                                        backupError =
                                            result.cause.toBackupError(
                                                defaultRes = R.string.error_backup_restore_failed,
                                            ),
                                    )
                                }
                            }
                            RestoreResult.Success -> {
                                transientState.update { it.copy(isRestoring = false, restoreSuccess = true) }
                            }
                        }
                    }
                }
                SettingsEvent.RestoreDismissed -> {
                    transientState.update { it.copy(showRestoreConfirmDialog = false, pendingRestoreFile = null) }
                }
                is SettingsEvent.ChangeBackupDirectory -> {
                    viewModelScope.launch {
                        _sideEffect.emit(SideEffect.TakePersistableUriPermission(event.path))
                        backupSettings.updateBackupDirectoryUri(event.path)
                    }
                }
                is SettingsEvent.DeleteLocalBackup -> {
                    viewModelScope.launch {
                        backupService
                            .deleteBackup(event.file.location)
                            .onFailure { e ->
                                logE("LocalBackupViewModel", e) { "Failed to delete backup" }
                                transientState.update {
                                    it.copy(backupError = UiText.StringRes(R.string.error_backup_delete_failed))
                                }
                            }
                        // Force refresh the list
                        transientState.update { it.copy(refreshTrigger = it.refreshTrigger + 1) }
                    }
                }
                SettingsEvent.DismissBackupError -> {
                    transientState.update { it.copy(backupError = null) }
                }
                SettingsEvent.OpenSetPasswordDialog -> {
                    transientState.update { it.copy(showSetPasswordDialog = true) }
                }
                SettingsEvent.DismissSetPasswordDialog -> {
                    transientState.update { it.copy(showSetPasswordDialog = false) }
                }
                is SettingsEvent.UpdateBackupPassword -> {
                    viewModelScope.launch {
                        val currentPrefs = settingsRepo.userPreferences.first()
                        val oldHash = currentPrefs.backupPasswordHash
                        val oldPassword = oldHash?.let { encryptionManager.decrypt(it) }

                        val newHash = if (event.raw.isBlank()) null else encryptionManager.encrypt(event.raw)

                        transientState.update { it.copy(isReencrypting = true, showSetPasswordDialog = false) }

                        // 1. Re-encrypt existing backups
                        backupService
                            .reencryptBackups(oldPassword, event.raw)
                            .onFailure { e ->
                                logE("LocalBackupViewModel", e) { "Backup re-encryption failed" }
                                transientState.update {
                                    it.copy(backupError = UiText.StringRes(R.string.error_backup_reencrypt_failed))
                                }
                            }

                        // 2. Update master password hash
                        backupSettings.updateBackupPasswordHash(newHash)

                        transientState.update { it.copy(isReencrypting = false) }

                        if (event.autoStartBackup) {
                            startBackup()
                        }
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
                    viewModelScope.launch { backupSettings.updateBackupSchedule(schedule = event.schedule) }
                else -> {}
            }
        }

        private suspend fun startBackup() {
            transientState.update { it.copy(isBackingUp = true, backupError = null) }
            backupService
                .createBackup()
                .onSuccess {
                    backupSettings.updateLastBackupTimestamp(System.currentTimeMillis())
                    transientState.update { it.copy(refreshTrigger = it.refreshTrigger + 1) }
                }.onFailure { e ->
                    transientState.update {
                        it.copy(
                            backupError = e.toBackupError(defaultRes = R.string.error_backup_create_failed),
                        )
                    }
                }
            transientState.update { it.copy(isBackingUp = false) }
        }

        private fun Throwable.toBackupError(defaultRes: Int): UiText =
            if (this is WrongBackupPasswordException) {
                UiText.StringRes(R.string.error_backup_wrong_password)
            } else {
                UiText.StringRes(defaultRes)
            }

        private data class TransientBackupState(
            val isBackingUp: Boolean = false,
            val isRestoring: Boolean = false,
            val isReencrypting: Boolean = false,
            val showRestoreConfirmDialog: Boolean = false,
            val showSetPasswordDialog: Boolean = false,
            val backupError: UiText? = null,
            val restoreSuccess: Boolean = false,
            val pendingRestoreFile: BackupFileInfo? = null,
            val passwordVerificationResult: Boolean? = null,
            val refreshTrigger: Int = 0,
        )
    }
