package app.readylytics.health.ui.sync

import androidx.lifecycle.viewModelScope
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.repository.SelectedDateRepository
import app.readylytics.health.domain.model.Result
import app.readylytics.health.domain.repository.HealthConnectPermissionRevokedException
import app.readylytics.health.domain.repository.HealthConnectRepository
import app.readylytics.health.domain.repository.PermissionStatus
import app.readylytics.health.domain.sync.ForegroundSyncController
import app.readylytics.health.ui.common.UiText
import app.readylytics.health.ui.common.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

sealed interface SyncUiState {
    data object CheckingPermissions : SyncUiState

    data object NeedsPermissions : SyncUiState

    data object Unavailable : SyncUiState

    data object PermissionsGranted : SyncUiState

    data object DiscoveringDevices : SyncUiState

    data class DeviceSelectionReady(
        val devices: List<String>,
    ) : SyncUiState

    data object SyncingCatchUp : SyncUiState

    data class Error(
        val message: UiText,
    ) : SyncUiState
}

@HiltViewModel
class SyncViewModel
    @Inject
    constructor(
        private val hcRepo: HealthConnectRepository,
        private val foregroundSyncController: ForegroundSyncController,
        private val settingsRepo: SettingsRepository,
        private val selectedDateRepository: SelectedDateRepository,
    ) : BaseViewModel() {
        fun validateSyncNecessary(): Result<Unit> = Result.success(Unit)

        private val _uiState = MutableStateFlow<SyncUiState>(SyncUiState.CheckingPermissions)
        val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

        val userPreferences = settingsRepo.userPreferences
        val requiredPermissions = hcRepo.requiredPermissions
        val allPermissions = hcRepo.allPermissions
        val isSyncing = foregroundSyncController.isSyncing
        val recalcProgress = foregroundSyncController.recalcProgress

        private val _syncEvents = Channel<SyncEvent>(capacity = Channel.BUFFERED)
        val syncEvents = _syncEvents.receiveAsFlow()

        private var foregroundCheckJob: Job? = null

        init {
            viewModelScope.launch {
                foregroundSyncController.syncCompletedEvent.collect {
                    _syncEvents.trySend(SyncEvent.SyncCompleted)
                }
            }

            // Initialize installDate if it's 0L
            viewModelScope.launch {
                userPreferences.collectLatest { prefs ->
                    if (prefs.installDate == 0L) {
                        settingsRepo.updateInstallDate(System.currentTimeMillis())
                    }
                }
            }
        }

        fun triggerManualSync() {
            viewModelScope.launch {
                try {
                    // Pull-to-refresh recalculates the current day only; full historical recalculation
                    // is exposed via the Settings "Resync Health Connect data" button (WorkManager).
                    foregroundSyncController.triggerDailySync()
                } catch (e: HealthConnectPermissionRevokedException) {
                    _uiState.update { SyncUiState.NeedsPermissions }
                } catch (e: Exception) {
                    app.readylytics.health.domain.util
                        .logE("SyncViewModel", e) { "Manual sync failed" }
                    _uiState.update { SyncUiState.Error(UiText.StringRes(app.readylytics.health.R.string.error_sync_failed)) }
                }
            }
        }

        fun onAppForeground() {
            foregroundCheckJob?.cancel()
            viewModelScope.launch {
                // Only reset to today if the system date has changed (past midnight)
                if (selectedDateRepository.selectedDate.value != LocalDate.now()) {
                    selectedDateRepository.resetToToday()
                }
            }
            // onPermissionsGranted() sets SyncingCatchUp synchronously before launching
            // its coroutine. If that already happened, skip the permission re-check.
            // Samsung's getGrantedPermissions() returns stale Missing immediately after
            // granting, so this prevents it from overwriting a valid grant result.
            if (_uiState.value is SyncUiState.SyncingCatchUp) return
            if (_uiState.value is SyncUiState.PermissionsGranted) {
                viewModelScope.launch {
                    try {
                        foregroundSyncController.evaluateAndSync()
                    } catch (e: HealthConnectPermissionRevokedException) {
                        _uiState.update { SyncUiState.NeedsPermissions }
                    } catch (e: Exception) {
                        app.readylytics.health.domain.util.logE(
                            "SyncViewModel",
                            e,
                        ) { "Foreground sync eval failed" }
                    }
                }
                return
            }
            foregroundCheckJob =
                viewModelScope.launch {
                    app.readylytics.health.domain.util.logD("SyncViewModel") {
                        "App foregrounded. Starting permission check..."
                    }
                    _uiState.update { SyncUiState.CheckingPermissions }
                    try {
                        when (val status = hcRepo.checkPermissions()) {
                            is PermissionStatus.Granted -> {
                                app.readylytics.health.domain.util.logD("SyncViewModel") {
                                    "Permissions GRANTED (critical ones present). Starting sync..."
                                }
                                _uiState.update { SyncUiState.PermissionsGranted }
                                try {
                                    foregroundSyncController.evaluateAndSync()
                                } catch (e: HealthConnectPermissionRevokedException) {
                                    // Re-verify before going to onboarding. Avoids misrouting
                                    // errors from specific record types (e.g. missing
                                    // READ_RESTING_HEART_RATE) as full permission revocation.
                                    val recheck = hcRepo.checkPermissions()
                                    if (recheck !is PermissionStatus.Granted) {
                                        _uiState.update { SyncUiState.NeedsPermissions }
                                    }
                                } catch (e: Exception) {
                                    app.readylytics.health.domain.util.logE("SyncViewModel", e) {
                                        "Foreground sync failed, staying on MainShell"
                                    }
                                }
                            }
                            is PermissionStatus.Missing -> {
                                app.readylytics.health.domain.util.logD(
                                    "SyncViewModel",
                                ) { "Permissions MISSING: ${status.missing}" }
                                _uiState.update { SyncUiState.NeedsPermissions }
                            }
                            is PermissionStatus.Unavailable -> {
                                app.readylytics.health.domain.util.logD(
                                    "SyncViewModel",
                                ) { "Health Connect UNAVAILABLE" }
                                _uiState.update { SyncUiState.Unavailable }
                            }
                        }
                    } catch (e: HealthConnectPermissionRevokedException) {
                        app.readylytics.health.domain.util.logD(
                            "SyncViewModel",
                        ) { "Permissions revoked during check" }
                        _uiState.update { SyncUiState.NeedsPermissions }
                    } catch (e: Exception) {
                        app.readylytics.health.domain.util
                            .logE("SyncViewModel", e) { "Foreground sync failed" }
                        _uiState.update { SyncUiState.Error(UiText.StringRes(app.readylytics.health.R.string.error_sync_failed)) }
                    }
                }
        }

        fun onPermissionsGranted() {
            foregroundCheckJob?.cancel()
            _uiState.update { SyncUiState.SyncingCatchUp }
            viewModelScope.launch {
                try {
                    app.readylytics.health.domain.util.logD("SyncViewModel") {
                        "Permissions granted. Starting sync with all devices..."
                    }
                    foregroundSyncController.triggerImmediateSync()
                    app.readylytics.health.domain.util.logD("SyncViewModel") {
                        "Initial sync completed. User can select device in settings later."
                    }
                    _uiState.update { SyncUiState.PermissionsGranted }
                } catch (e: HealthConnectPermissionRevokedException) {
                    app.readylytics.health.domain.util.logE("SyncViewModel", e) {
                        "Initial sync: permissions revoked"
                    }
                    _uiState.update { SyncUiState.NeedsPermissions }
                } catch (e: Exception) {
                    app.readylytics.health.domain.util.logE("SyncViewModel", e) {
                        "Initial sync failed"
                    }
                    _uiState.update {
                        SyncUiState.Error(UiText.StringRes(app.readylytics.health.R.string.error_sync_failed))
                    }
                }
            }
        }

        fun onDeviceSelected() {
            _uiState.update { SyncUiState.SyncingCatchUp }
            viewModelScope.launch {
                try {
                    foregroundSyncController.triggerImmediateSync()
                } catch (e: HealthConnectPermissionRevokedException) {
                    val recheck = hcRepo.checkPermissions()
                    if (recheck !is PermissionStatus.Granted) {
                        _uiState.update { SyncUiState.NeedsPermissions }
                        return@launch
                    }
                    app.readylytics.health.domain.util.logE("SyncViewModel", e) {
                        "Sync threw permission error but HC says granted — proceeding"
                    }
                } catch (e: Exception) {
                    app.readylytics.health.domain.util.logE("SyncViewModel", e) {
                        "Post-device-selection sync failed"
                    }
                }
                _uiState.update { SyncUiState.PermissionsGranted }
            }
        }

        fun onPermissionsDenied() {
            foregroundCheckJob?.cancel()
            _uiState.update { SyncUiState.NeedsPermissions }
        }
    }

sealed interface SyncEvent {
    data object SyncCompleted : SyncEvent
}
