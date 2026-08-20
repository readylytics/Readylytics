package app.readylytics.health.core.model.domain.logcat

import java.io.File

interface LogcatCaptureStore {
    suspend fun capture(durationMinutes: Int): String?

    fun captureFile(): File
}
