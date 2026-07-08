package app.readylytics.health.data.logcat

import android.content.Context
import app.readylytics.health.domain.logcat.LogcatCaptureStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LogcatCaptureStoreImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : LogcatCaptureStore {
        // Non-privileged apps can only read logcat lines from their own UID since API 16,
        // so no READ_LOGS permission or runtime request is needed here.
        override suspend fun capture(durationMinutes: Int): String? =
            withContext(Dispatchers.IO) {
                try {
                    val sinceTime = formatSinceTime(durationMinutes)
                    val process =
                        ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", sinceTime)
                            .redirectErrorStream(true)
                            .start()
                    val output = process.inputStream.bufferedReader().use { it.readText() }
                    process.waitFor()
                    if (output.isBlank()) return@withContext null
                    val file = captureFile()
                    file.parentFile?.mkdirs()
                    file.writeText(output)
                    output
                } catch (e: IOException) {
                    null
                } catch (e: InterruptedException) {
                    null
                }
            }

        override fun captureFile(): File = File(File(context.cacheDir, LOGCAT_CAPTURE_DIR), LOGCAT_CAPTURE_FILE)

        companion object {
            const val LOGCAT_CAPTURE_DIR = "logcat_capture"
            const val LOGCAT_CAPTURE_FILE = "logcat_capture.txt"

            private val SINCE_TIME_FORMAT
                get() = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

            internal fun formatSinceTime(durationMinutes: Int): String {
                val since = Date(System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(durationMinutes.toLong()))
                return SINCE_TIME_FORMAT.format(since)
            }
        }
    }
