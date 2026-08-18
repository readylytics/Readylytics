package app.readylytics.health.util

import android.content.Context
import android.util.Log
import app.readylytics.health.data.security.SecureFileStore
import app.readylytics.health.data.security.TinkSecureFileStore
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds

class SecureFileLogSink(
    private val context: Context,
    private val maxFileSize: Long = DEFAULT_MAX_FILE_SIZE_BYTES,
    private val maxBackups: Int = DEFAULT_MAX_BACKUPS,
    private val encryptStreams: Boolean = true,
    coroutineContext: CoroutineContext = Dispatchers.IO.limitedParallelism(1),
    private val secureFileStore: SecureFileStore = TinkSecureFileStore.create(context),
    private val flushLineThreshold: Int = DEFAULT_FLUSH_LINE_THRESHOLD,
    private val flushIntervalMs: Long = DEFAULT_FLUSH_INTERVAL_MS,
) : DomainLogSink {
    private val writeDispatcher: CoroutineContext = coroutineContext
    private val logDirectory = File(context.cacheDir, "logs")
    private val scope = CoroutineScope(SupervisorJob() + writeDispatcher)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    // Slot rotation, crypto, and the active-slot memory buffer live here; this class only formats,
    // sanitizes, and batches. Confined to the single-threaded [writeDispatcher], so LogSlotStore
    // needs no locking of its own.
    private val slotStore =
        LogSlotStore(
            logDirectory = logDirectory,
            maxFileSize = maxFileSize,
            maxBackups = maxBackups,
            encryptStreams = encryptStreams,
            secureFileStore = secureFileStore,
        )

    // Memory buffer for logs
    private val pendingLogs = mutableListOf<String>()
    private var lastWriteTimestamp = System.currentTimeMillis()
    private var flushJob: Job? = null

    init {
        if (!logDirectory.exists()) {
            logDirectory.mkdirs()
        }
    }

    // Release sink: DEBUG is developer chatter (per-day sync narration, per-batch ingest counts) and
    // is dropped before the message lambda even runs -- DomainLogger.log checks isLoggable first.
    // INFO/WARN/ERROR still reach the encrypted diagnostic file.
    override fun isLoggable(
        level: LogLevel,
        tag: String,
    ): Boolean = level != LogLevel.DEBUG

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
            LogLevel.DEBUG -> Log.d(tag, formattedMessage)
            LogLevel.INFO -> Log.i(tag, formattedMessage)
            LogLevel.WARN -> Log.w(tag, formattedMessage, throwable)
            LogLevel.ERROR -> Log.e(tag, formattedMessage, throwable)
        }

        // Offload file writing to serialization coroutine scope
        scope.launch {
            try {
                bufferLog(level, tag, message, throwable, context)
            } catch (e: Exception) {
                // Deliberately broad: this is the logging sink itself, running detached in
                // `scope.launch`. bufferLog does file I/O, formatting and sanitisation, so a
                // narrower type would let an unexpected failure escape into the scope's handler
                // and take down logging (or the app) because a log line could not be written.
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
        val sanitizedMessage = sanitizeLogMessage(message)
        val sanitizedStackTrace = throwable?.let { sanitizeLogMessage(Log.getStackTraceString(it)) }
        val logLine =
            "$timestamp [$level] [$tag] [Session:$sessionId] $sanitizedMessage" +
                (sanitizedStackTrace?.let { "\n$it" } ?: "") + "\n"

        pendingLogs.add(logLine)

        val timeSinceLastWrite = System.currentTimeMillis() - lastWriteTimestamp
        if (pendingLogs.size >= flushLineThreshold || timeSinceLastWrite >= flushIntervalMs) {
            flush(fromSchedule = false)
        } else {
            if (flushJob == null) {
                val delayTime = (flushIntervalMs - timeSinceLastWrite).coerceAtLeast(0)
                flushJob =
                    scope.launch {
                        delay(delayTime.milliseconds)
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
        val lines = pendingLogs.toList()
        pendingLogs.clear()

        slotStore.appendLines(lines)

        lastWriteTimestamp = System.currentTimeMillis()
    }

    // Safe decryption exposure helper for internal diagnostics use
    suspend fun readLogsDecrypted(): String =
        withContext(writeDispatcher) {
            flush(fromSchedule = false)
            slotStore.readAll()
        }

    companion object {
        // 512 KB x 12 slots keeps total retention at the historical ~6 MB while bounding both the
        // in-memory active-slot buffer and the per-flush encrypt to 512 KB (F2). Before F2 a flush
        // decrypted and rewrote all 6 MB.
        const val DEFAULT_MAX_FILE_SIZE_BYTES: Long = 512L * 1024L
        const val DEFAULT_MAX_BACKUPS: Int = 11

        // Raised from 5 lines / 2 s: safe now that a flush costs one small encrypt instead of a
        // full decrypt-rewrite cycle. The 2 s -> 5 s interval keeps the durability window (pending
        // lines lost on process death) the same order of magnitude; the 5 -> 64 line threshold does
        // not (up to ~13x more lines can be pending). Accepted because readLogsDecrypted() always
        // flushes first, so a user-initiated export never misses them.
        const val DEFAULT_FLUSH_LINE_THRESHOLD: Int = 64
        const val DEFAULT_FLUSH_INTERVAL_MS: Long = 5_000L

        internal fun sanitizeLogMessage(message: String): String {
            var sanitized = message

            // Redact UUIDs
            sanitized =
                sanitized.replace(
                    Regex(
                        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
                        RegexOption.IGNORE_CASE,
                    ),
                    "***",
                )

            // Redact specific health metrics numbers
            sanitized =
                sanitized.replace(
                    Regex("(?i)\\b(HR|HRV|BP|BPM)\\s*[:=]?\\s*(?:is\\s*)?\\d+(?:\\.\\d+)?(?:/\\d+)?"),
                    "$1 ***",
                )

            return sanitized
        }
    }
}
