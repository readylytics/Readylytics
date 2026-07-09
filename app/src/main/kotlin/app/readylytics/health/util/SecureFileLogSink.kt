package app.readylytics.health.util

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKeys
import app.readylytics.health.domain.util.DomainLogSink
import app.readylytics.health.domain.util.LogContext
import app.readylytics.health.domain.util.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.CoroutineContext

class SecureFileLogSink(
    private val context: Context,
    private val maxFileSize: Long = 500 * 1024L, // 500 KB
    private val maxBackups: Int = 2,
    private val encryptStreams: Boolean = true,
    coroutineContext: CoroutineContext = Dispatchers.IO.limitedParallelism(1),
) : DomainLogSink {
    private val writeDispatcher: CoroutineContext = coroutineContext
    private val logDirectory = File(context.cacheDir, "logs")
    private val logFile = File(logDirectory, "prod_logs.txt")
    private val scope = CoroutineScope(SupervisorJob() + writeDispatcher)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    // Memory buffer for logs
    private val pendingLogs = mutableListOf<String>()
    private var lastWriteTimestamp = System.currentTimeMillis()
    private var flushJob: Job? = null

    init {
        if (!logDirectory.exists()) {
            logDirectory.mkdirs()
        }
    }

    override fun log(
        level: LogLevel,
        tag: String,
        message: String,
        throwable: Throwable?,
        context: LogContext,
    ) {
        // Log to standard Logcat for developers/debugging in real-time
        val formattedMessage = "[Session:${context.sessionId ?: "none"}] $message"
        when (level) {
            LogLevel.INFO -> Log.i(tag, formattedMessage)
            LogLevel.WARN -> Log.w(tag, formattedMessage, throwable)
            LogLevel.ERROR -> Log.e(tag, formattedMessage, throwable)
        }

        // Offload file writing to serialization coroutine scope
        scope.launch {
            try {
                bufferLog(level, tag, message, throwable, context)
            } catch (e: Exception) {
                Log.e("SecureFileLogSink", "Failed to write log to file", e)
            }
        }
    }

    private fun bufferLog(
        level: LogLevel,
        tag: String,
        message: String,
        throwable: Throwable?,
        logContext: LogContext,
    ) {
        val timestamp = dateFormat.format(Date())
        val sessionId = logContext.sessionId ?: "none"
        val logLine =
            "$timestamp [$level] [$tag] [Session:$sessionId] $message" +
                (throwable?.let { "\n${Log.getStackTraceString(it)}" } ?: "") + "\n"

        pendingLogs.add(logLine)

        val timeSinceLastWrite = System.currentTimeMillis() - lastWriteTimestamp
        if (pendingLogs.size >= 5 || timeSinceLastWrite >= 2000) {
            flush(fromSchedule = false)
        } else {
            if (flushJob == null) {
                val delayTime = (2000 - timeSinceLastWrite).coerceAtLeast(0)
                flushJob =
                    scope.launch {
                        delay(delayTime)
                        flush(fromSchedule = true)
                    }
            }
        }
    }

    private fun flush(fromSchedule: Boolean = false) {
        if (!fromSchedule) {
            flushJob?.cancel()
        }
        flushJob = null

        if (pendingLogs.isEmpty()) return
        val content = pendingLogs.joinToString("")
        pendingLogs.clear()

        checkRotation()

        if (encryptStreams) {
            writeEncrypted(content)
        } else {
            writePlain(content)
        }

        lastWriteTimestamp = System.currentTimeMillis()
    }

    private fun writeEncrypted(content: String) {
        val encryptedFile = getEncryptedFile(logFile)
        val tempFile = File(logDirectory, "temp_write.txt")

        // EncryptedFile doesn't support append cleanly, so we read, append, and rewrite.
        // Since our max file size is small (500KB), this is fast and safe.
        val existingContent =
            if (logFile.exists()) {
                try {
                    encryptedFile.openFileInput().use { it.bufferedReader().readText() }
                } catch (e: Exception) {
                    ""
                }
            } else {
                ""
            }

        val updatedContent = existingContent + content

        val tempEncrypted = getEncryptedFile(tempFile)
        if (tempFile.exists()) tempFile.delete()

        tempEncrypted.openFileOutput().use { output ->
            output.write(updatedContent.toByteArray(Charsets.UTF_8))
        }

        if (logFile.exists()) logFile.delete()
        tempFile.renameTo(logFile)
    }

    private fun writePlain(content: String) {
        FileOutputStream(logFile, true).use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
        }
    }

    private fun getEncryptedFile(file: File): EncryptedFile {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedFile
            .Builder(
                file,
                context,
                masterKeyAlias,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
            ).build()
    }

    private fun checkRotation() {
        if (!logFile.exists() || logFile.length() < maxFileSize) return

        if (maxBackups <= 0) {
            logFile.delete()
            return
        }

        // Rotate backups
        for (i in maxBackups - 1 downTo 1) {
            val src = File(logDirectory, "prod_logs.txt.$i")
            val dest = File(logDirectory, "prod_logs.txt.${i + 1}")
            if (src.exists()) {
                if (dest.exists()) dest.delete()
                src.renameTo(dest)
            }
        }

        val firstBackup = File(logDirectory, "prod_logs.txt.1")
        if (firstBackup.exists()) firstBackup.delete()
        logFile.renameTo(firstBackup)
    }

    // Safe decryption exposure helper for internal diagnostics use
    suspend fun readLogsDecrypted(): String =
        withContext(writeDispatcher) {
            flush(fromSchedule = false)

            val files = mutableListOf<File>()
            for (i in maxBackups downTo 1) {
                files.add(File(logDirectory, "prod_logs.txt.$i"))
            }
            files.add(logFile)

            val result = StringBuilder()
            for (file in files) {
                if (file.exists()) {
                    try {
                        val fileContent =
                            if (encryptStreams) {
                                getEncryptedFile(file).openFileInput().use { it.bufferedReader().readText() }
                            } else {
                                file.readText()
                            }
                        result.append(fileContent)
                    } catch (e: Exception) {
                        result.append("Error reading encrypted log file: ${e.message}\n")
                    }
                }
            }
            result.toString()
        }
}
