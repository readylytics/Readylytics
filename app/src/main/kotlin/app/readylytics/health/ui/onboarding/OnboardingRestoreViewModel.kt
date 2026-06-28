package app.readylytics.health.ui.onboarding

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.readylytics.health.R
import app.readylytics.health.data.backup.WrongBackupPasswordException
import app.readylytics.health.domain.backup.BackupLocation
import app.readylytics.health.domain.backup.RestoreResult
import app.readylytics.health.domain.backup.RestoreService
import app.readylytics.health.core.ui.common.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OnboardingRestoreState(
    val isValidating: Boolean = false,
    val isRestoring: Boolean = false,
    val error: UiText? = null,
    val restoreRequiresRestart: Boolean = false,
)

@HiltViewModel
class OnboardingRestoreViewModel
    @Inject
    constructor(
        private val restoreService: RestoreService,
    ) : ViewModel() {
        sealed interface SideEffect {
            data object RestartApp : SideEffect
        }

        private val _sideEffect = Channel<SideEffect>(Channel.BUFFERED)
        val sideEffect: Flow<SideEffect> = _sideEffect.receiveAsFlow()

        private val _state = MutableStateFlow(OnboardingRestoreState())
        val state: StateFlow<OnboardingRestoreState> = _state.asStateFlow()

        fun restore(
            uri: Uri,
            password: String,
        ) {
            viewModelScope.launch {
                val location = BackupLocation(uri.toString())
                _state.update { it.copy(isValidating = true, error = null) }
                restoreService
                    .validate(location, password)
                    .onSuccess {
                        _state.update { it.copy(isValidating = false, isRestoring = true) }
                        when (val result = restoreService.applyRestore(location, password)) {
                            RestoreResult.SuccessRequiresRestart -> _sideEffect.send(SideEffect.RestartApp)
                            RestoreResult.Success -> _state.update { it.copy(isRestoring = false) }
                            is RestoreResult.PartialSuccessRequiresRestart -> {
                                _state.update {
                                    it.copy(
                                        isRestoring = false,
                                        error = UiText.StringRes(R.string.restore_partial_success_message),
                                        restoreRequiresRestart = true,
                                    )
                                }
                                _sideEffect.send(SideEffect.RestartApp)
                            }
                            is RestoreResult.Failure ->
                                _state.update { it.copy(isRestoring = false, error = result.cause.toUiText()) }
                        }
                    }.onFailure { e ->
                        _state.update { it.copy(isValidating = false, error = e.toUiText()) }
                    }
            }
        }

        fun dismissError() {
            _state.update { it.copy(error = null) }
        }

        private fun Throwable.toUiText(): UiText =
            if (this is WrongBackupPasswordException) {
                UiText.StringRes(R.string.error_backup_wrong_password)
            } else {
                UiText.StringRes(R.string.error_backup_restore_validation)
            }
    }
