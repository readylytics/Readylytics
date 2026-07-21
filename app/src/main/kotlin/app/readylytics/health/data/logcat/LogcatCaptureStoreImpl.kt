package app.readylytics.health.data.logcat

import android.content.Context
import android.content.pm.ApplicationInfo
import app.readylytics.health.di.IoDispatcher
import app.readylytics.health.di.ReleaseLogSink
import app.readylytics.health.domain.logcat.LogcatCaptureStore
import app.readylytics.health.util.SecureFileLogSink
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
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
        @ReleaseLogSink private val logSink: SecureFileLogSink,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : LogcatCaptureStore {
        internal var debugLogReader: suspend (Int) -> String? = ::readFromLogcat

        override suspend fun capture(durationMinutes: Int): String? =
            withContext(ioDispatcher) {
                try {
                    val logs =
                        if (isDebugBuild()) {
                            debugLogReader(durationMinutes)
                        } else {
                            logSink.readLogsDecrypted()
                        } ?: return@withContext null
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

        private fun isDebugBuild(): Boolean = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

        private fun readFromLogcat(durationMinutes: Int): String? {
            val process =
                Runtime
                    .getRuntime()
                    .exec(arrayOf("logcat", "-d"))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            return output.takeIf { it.isNotBlank() }
        }

        companion object {
            const val LOGCAT_CAPTURE_DIR = "logcat_capture"
            const val LOGCAT_CAPTURE_FILE = "logcat_capture.txt"
        }
    }
