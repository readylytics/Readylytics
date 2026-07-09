package app.readylytics.health.data.logcat

import android.content.Context
import app.readylytics.health.domain.logcat.LogcatCaptureStore
import app.readylytics.health.util.SecureFileLogSink
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LogcatCaptureStoreImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : LogcatCaptureStore {
        internal var logSink: SecureFileLogSink? = null

        override suspend fun capture(durationMinutes: Int): String? =
            withContext(Dispatchers.IO) {
                try {
                    // Bypassing physical system shell logcat entirely.
                    // Read from our encrypted size-bounded log file instead.
                    val sink = logSink ?: SecureFileLogSink(context)
                    val logs = sink.readLogsDecrypted()
                    if (logs.isBlank()) return@withContext null

                    val file = captureFile()
                    file.parentFile?.mkdirs()
                    file.writeText(logs)
                    logs
                } catch (e: IOException) {
                    null
                }
            }

        override fun captureFile(): File = File(File(context.cacheDir, LOGCAT_CAPTURE_DIR), LOGCAT_CAPTURE_FILE)

        companion object {
            const val LOGCAT_CAPTURE_DIR = "logcat_capture"
            const val LOGCAT_CAPTURE_FILE = "logcat_capture.txt"
        }
    }
