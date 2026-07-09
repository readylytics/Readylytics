package app.readylytics.health.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.readylytics.health.domain.logcat.LogcatCaptureStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingLogViewModel
    @Inject
    constructor(
        private val logcatCaptureStore: LogcatCaptureStore,
    ) : ViewModel() {
        private val _logText = MutableStateFlow<String?>(null)
        val logText: StateFlow<String?> = _logText.asStateFlow()

        private var pollingJob: Job? = null

        fun startPolling() {
            if (pollingJob != null) return
            pollingJob =
                viewModelScope.launch {
                    while (isActive) {
                        val captured = logcatCaptureStore.capture(durationMinutes = 3)
                        _logText.value = captured
                        delay(2000)
                    }
                }
        }

        fun stopPolling() {
            pollingJob?.cancel()
            pollingJob = null
        }

        override fun onCleared() {
            super.onCleared()
            stopPolling()
        }
    }
