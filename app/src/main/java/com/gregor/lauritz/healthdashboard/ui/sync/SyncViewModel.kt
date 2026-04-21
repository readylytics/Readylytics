package com.gregor.lauritz.healthdashboard.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.healthconnect.HealthConnectRepository
import com.gregor.lauritz.healthdashboard.data.healthconnect.PermissionStatus
import com.gregor.lauritz.healthdashboard.domain.sync.ForegroundSyncController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    ) : ViewModel() {
        private val _uiState = MutableStateFlow<SyncUiState>(SyncUiState.CheckingPermissions)
        val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

        fun onAppForeground() {
            viewModelScope.launch {
                _uiState.value = SyncUiState.CheckingPermissions
                when (val status = hcRepo.checkPermissions()) {
                    is PermissionStatus.Granted -> {
                        _uiState.value = SyncUiState.PermissionsGranted
                        foregroundSyncController
                            .evaluateAndSync()
                    }
                    is PermissionStatus.Missing -> {
                        _uiState.value = SyncUiState.NeedsPermissions
                    }
                    is PermissionStatus.Unavailable -> {
                        _uiState.value = SyncUiState.Unavailable
                    }
                }
            }
        }

        fun onPermissionsGranted() {
            viewModelScope.launch {
                _uiState.value = SyncUiState.SyncingCatchUp
                foregroundSyncController.triggerImmediateSync()
                _uiState.value = SyncUiState.PermissionsGranted
            }
        }
    }
