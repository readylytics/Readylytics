package app.readylytics.health.domain.logcat

import java.io.File

interface LogcatCaptureStore {
    suspend fun capture(durationMinutes: Int): String?

    fun captureFile(): File
}
