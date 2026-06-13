package app.readylytics.health.ui.onboarding

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.readylytics.health.R
import app.readylytics.health.data.backup.WrongBackupPasswordException
import app.readylytics.health.domain.backup.RestoreResult
import app.readylytics.health.domain.backup.RestoreService
import app.readylytics.health.ui.common.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OnboardingRestoreState(
    val isValidating: Boolean = false,
    val isRestoring: Boolean = false,
    val error: UiText? = null,
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

        private val _sideEffect = MutableSharedFlow<SideEffect>()
        val sideEffect: SharedFlow<SideEffect> = _sideEffect.asSharedFlow()

        private val _state = MutableStateFlow(OnboardingRestoreState())
        val state: StateFlow<OnboardingRestoreState> = _state.asStateFlow()

        fun restore(
            uri: Uri,
            password: String,
        ) {
            viewModelScope.launch {
                _state.update { it.copy(isValidating = true, error = null) }
                restoreService
                    .validate(uri, password)
                    .onSuccess {
                        _state.update { it.copy(isValidating = false, isRestoring = true) }
                        when (val result = restoreService.applyRestore(uri, password)) {
                            RestoreResult.SuccessRequiresRestart -> _sideEffect.emit(SideEffect.RestartApp)
                            RestoreResult.Success -> _state.update { it.copy(isRestoring = false) }
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
                message?.let { UiText.RawString(it) } ?: UiText.StringRes(R.string.error_backup_restore_validation)
            }
    }
