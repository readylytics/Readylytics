package com.gregor.lauritz.healthdashboard.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.healthconnect.HealthConnectPermissionRevokedException
import com.gregor.lauritz.healthdashboard.data.healthconnect.HealthConnectRepository
import com.gregor.lauritz.healthdashboard.data.healthconnect.PermissionStatus
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.domain.sync.ForegroundSyncController
import dagger.hilt.android.lifecycle.HiltViewModel
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
                    _uiState.update { SyncUiState.Error(e.message ?: "Sync failed") }
                }
            }
        }

        fun onAppForeground() {
            viewModelScope.launch {
                _uiState.update { SyncUiState.CheckingPermissions }
                try {
                    when (val status = hcRepo.checkPermissions()) {
                        is PermissionStatus.Granted -> {
                            _uiState.update { SyncUiState.PermissionsGranted }
                            foregroundSyncController
                                .evaluateAndSync()
                        }
                        is PermissionStatus.Missing -> {
                            _uiState.update { SyncUiState.NeedsPermissions }
                        }
                        is PermissionStatus.Unavailable -> {
                            _uiState.update { SyncUiState.Unavailable }
                        }
                    }
                } catch (e: HealthConnectPermissionRevokedException) {
                    _uiState.update { SyncUiState.NeedsPermissions }
                } catch (e: Exception) {
                    _uiState.update { SyncUiState.Error(e.message ?: "Permission check failed") }
                }
            }
        }

        fun onPermissionsGranted() {
            viewModelScope.launch {
                _uiState.update { SyncUiState.SyncingCatchUp }
                try {
                    foregroundSyncController.triggerImmediateSync()
                    _uiState.update { SyncUiState.PermissionsGranted }
                } catch (e: HealthConnectPermissionRevokedException) {
                    _uiState.update { SyncUiState.NeedsPermissions }
                } catch (e: Exception) {
                    _uiState.update { SyncUiState.Error(e.message ?: "Post-permission sync failed") }
                }
            }
        }
    }

sealed interface SyncEvent {
    data object SyncCompleted : SyncEvent
}
