package com.gregor.lauritz.healthdashboard.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.domain.repository.HealthConnectPermissionRevokedException
import com.gregor.lauritz.healthdashboard.domain.repository.HealthConnectRepository
import com.gregor.lauritz.healthdashboard.domain.repository.PermissionStatus
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.domain.sync.ForegroundSyncController
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
import javax.inject.Inject

sealed interface SyncUiState {
    data object CheckingPermissions : SyncUiState

    data object NeedsPermissions : SyncUiState

    data object Unavailable : SyncUiState

    data object PermissionsGranted : SyncUiState

    data object SyncingCatchUp : SyncUiState

    data class Error(
        val message: String,
    ) : SyncUiState
}

@HiltViewModel
class SyncViewModel
    @Inject
    constructor(
        private val hcRepo: HealthConnectRepository,
        private val foregroundSyncController: ForegroundSyncController,
        private val settingsRepo: SettingsRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow<SyncUiState>(SyncUiState.CheckingPermissions)
        val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

        val userPreferences = settingsRepo.userPreferences
        val requiredPermissions = hcRepo.requiredPermissions
        val isSyncing = foregroundSyncController.isSyncing

        private val _syncEvents = Channel<SyncEvent>()
        val syncEvents = _syncEvents.receiveAsFlow()

        private var foregroundCheckJob: Job? = null

        init {
            viewModelScope.launch {
                foregroundSyncController.syncCompletedEvent.collect {
                    _syncEvents.send(SyncEvent.SyncCompleted)
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
                    foregroundSyncController.triggerImmediateSync()
                } catch (e: HealthConnectPermissionRevokedException) {
                    _uiState.update { SyncUiState.NeedsPermissions }
                } catch (e: Exception) {
                    com.gregor.lauritz.healthdashboard.domain.util.logE("SyncViewModel", e) { "Manual sync failed" }
                    _uiState.update { SyncUiState.Error(e.message ?: "Sync failed") }
                }
            }
        }

        fun onAppForeground() {
            foregroundCheckJob?.cancel()
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
                        com.gregor.lauritz.healthdashboard.domain.util.logE("SyncViewModel", e) { "Foreground sync eval failed" }
                    }
                }
                return
            }
            foregroundCheckJob = viewModelScope.launch {
                com.gregor.lauritz.healthdashboard.domain.util.logD("SyncViewModel") { "App foregrounded. Starting permission check..." }
                _uiState.update { SyncUiState.CheckingPermissions }
                try {
                    when (val status = hcRepo.checkPermissions()) {
                        is PermissionStatus.Granted -> {
                            com.gregor.lauritz.healthdashboard.domain.util.logD("SyncViewModel") { "Permissions GRANTED (critical ones present). Starting sync..." }
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
                                com.gregor.lauritz.healthdashboard.domain.util.logE("SyncViewModel", e) { "Foreground sync failed, staying on MainShell" }
                            }
                        }
                        is PermissionStatus.Missing -> {
                            com.gregor.lauritz.healthdashboard.domain.util.logD("SyncViewModel") { "Permissions MISSING: ${status.missing}" }
                            _uiState.update { SyncUiState.NeedsPermissions }
                        }
                        is PermissionStatus.Unavailable -> {
                            com.gregor.lauritz.healthdashboard.domain.util.logD("SyncViewModel") { "Health Connect UNAVAILABLE" }
                            _uiState.update { SyncUiState.Unavailable }
                        }
                    }
                } catch (e: HealthConnectPermissionRevokedException) {
                    com.gregor.lauritz.healthdashboard.domain.util.logD("SyncViewModel") { "Permissions revoked during check" }
                    _uiState.update { SyncUiState.NeedsPermissions }
                } catch (e: Exception) {
                    com.gregor.lauritz.healthdashboard.domain.util.logE("SyncViewModel", e) { "Foreground sync failed" }
                    _uiState.update { SyncUiState.Error(e.message ?: "Permission check failed") }
                }
            }
        }

        fun onPermissionsGranted() {
            foregroundCheckJob?.cancel()
            // Set state synchronously before launching the coroutine so that
            // onAppForeground() sees SyncingCatchUp immediately and bails out,
            // preventing Samsung's stale getGrantedPermissions() from overwriting.
            _uiState.update { SyncUiState.SyncingCatchUp }
            viewModelScope.launch {
                try {
                    foregroundSyncController.triggerImmediateSync()
                } catch (e: HealthConnectPermissionRevokedException) {
                    // Samsung throws SecurityException during data reads even immediately
                    // after granting in the dialog due to propagation delay. Re-verify
                    // before sending the user back to onboarding.
                    val recheck = hcRepo.checkPermissions()
                    if (recheck !is PermissionStatus.Granted) {
                        _uiState.update { SyncUiState.NeedsPermissions }
                        return@launch
                    }
                    // HC says granted — Samsung false positive. Proceed to main screen;
                    // background sync will pick up data on next foreground.
                    com.gregor.lauritz.healthdashboard.domain.util.logE("SyncViewModel", e) { "Sync threw permission error but HC says granted — proceeding" }
                } catch (e: Exception) {
                    // Sync failed for non-permission reason. Permissions are granted,
                    // so let the user into the app rather than blocking on onboarding.
                    com.gregor.lauritz.healthdashboard.domain.util.logE("SyncViewModel", e) { "Post-permission sync failed, proceeding to MainShell" }
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
