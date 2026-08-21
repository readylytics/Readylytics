package app.readylytics.health.ui.logcat

import androidx.lifecycle.ViewModel
import app.readylytics.health.core.model.domain.logcat.LogcatCaptureStore
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject

@HiltViewModel
class LogcatCaptureViewModel
    @Inject
    constructor(
        private val logcatCaptureStore: LogcatCaptureStore,
    ) : ViewModel() {
        suspend fun capture(durationMinutes: Int): String? = logcatCaptureStore.capture(durationMinutes)

        fun captureFile(): File = logcatCaptureStore.captureFile()
    }
