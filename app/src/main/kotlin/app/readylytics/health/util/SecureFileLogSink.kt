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
import java.io.FileOutputStream
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
        val sanitizedMessage = sanitizeLogMessage(message)
        val logLine =
            "$timestamp [$level] [$tag] [Session:$sessionId] $sanitizedMessage" +
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
        val content = pendingLogs.joinToString("")
        pendingLogs.clear()

        persistLogs(content)

        lastWriteTimestamp = System.currentTimeMillis()
    }

    private fun persistLogs(newContent: String) {
        val allLogs =
            buildString {
                append(readAllLogs())
                append(newContent)
            }
        val boundedLogs = retainWithinTotalCapacity(allLogs)
        val chunks = partitionIntoSlots(boundedLogs)
        writeChunks(chunks)
    }

    private fun readAllLogs(): String =
        buildString {
            for (file in orderedLogFiles()) {
                append(readFileContent(file))
            }
        }

    private fun orderedLogFiles(): List<File> {
        val files = mutableListOf<File>()
        for (i in maxBackups downTo 1) {
            files.add(File(logDirectory, "prod_logs.txt.$i"))
        }
        files.add(logFile)
        return files
    }

    private fun readFileContent(file: File): String {
        if (!file.exists()) return ""
        return try {
            if (encryptStreams) {
                secureFileStore.readText(file)
            } else {
                file.readText()
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun writeChunks(chunks: List<String>) {
        val slotFiles = orderedLogFiles()
        val normalizedChunks = chunks.takeLast(slotFiles.size)
        val emptyPrefixCount = slotFiles.size - normalizedChunks.size

        slotFiles.forEachIndexed { index, file ->
            val content =
                if (index < emptyPrefixCount) {
                    ""
                } else {
                    normalizedChunks[index - emptyPrefixCount]
                }
            writeFileContent(file, content)
        }
    }

    private fun writeFileContent(
        file: File,
        content: String,
    ) {
        if (file.exists()) {
            file.delete()
        }
        if (content.isEmpty()) return

        if (encryptStreams) {
            secureFileStore.writeText(file, content)
        } else {
            FileOutputStream(file, false).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
            }
        }
    }

    private fun retainWithinTotalCapacity(content: String): String {
        val totalCapacity = maxFileSize * (maxBackups + 1)
        if (content.toByteArray(Charsets.UTF_8).size <= totalCapacity) return content

        val segments = content.lineSegments().toMutableList()
        trimSegmentsToCapacity(segments, totalCapacity)
        return segments.joinToString("")
    }

    private fun trimSegmentsToCapacity(
        segments: MutableList<String>,
        capacityBytes: Long,
    ) {
        var totalBytes = segments.sumOf { it.byteSize() }.toLong()
        while (segments.isNotEmpty() && totalBytes > capacityBytes) {
            val first = segments.first()
            val firstBytes = first.byteSize().toLong()
            if (totalBytes - firstBytes >= capacityBytes) {
                segments.removeAt(0)
                totalBytes -= firstBytes
            } else {
                val keepBytes = (capacityBytes - (totalBytes - firstBytes)).toInt()
                segments[0] = first.takeLastUtf8Bytes(keepBytes)
                totalBytes = segments.sumOf { it.byteSize() }.toLong()
            }
        }
    }

    private fun partitionIntoSlots(content: String): List<String> {
        if (content.isEmpty()) return emptyList()

        val segments =
            content
                .lineSegments()
                .map { segment ->
                    if (segment.byteSize().toLong() <= maxFileSize) {
                        segment
                    } else {
                        segment.takeLastUtf8Bytes(maxFileSize.toInt())
                    }
                }

        val chunks = mutableListOf<String>()
        val currentChunk = StringBuilder()
        var currentChunkBytes = 0

        for (segment in segments) {
            val segmentBytes = segment.byteSize()
            if (currentChunkBytes > 0 && currentChunkBytes + segmentBytes > maxFileSize) {
                chunks.add(currentChunk.toString())
                currentChunk.clear()
                currentChunkBytes = 0
            }
            currentChunk.append(segment)
            currentChunkBytes += segmentBytes
        }

        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk.toString())
        }

        return chunks.takeLast(maxBackups + 1)
    }

    private fun String.lineSegments(): List<String> {
        if (isEmpty()) return emptyList()
        val result = mutableListOf<String>()
        var start = 0
        for (index in indices) {
            if (this[index] == '\n') {
                result.add(substring(start, index + 1))
                start = index + 1
            }
        }
        if (start < length) {
            result.add(substring(start))
        }
        return result
    }

    private fun String.byteSize(): Int = toByteArray(Charsets.UTF_8).size

    private fun String.takeLastUtf8Bytes(maxBytes: Int): String {
        if (maxBytes <= 0 || isEmpty()) return ""

        var bytes = 0
        var startIndex = length
        while (startIndex > 0) {
            val codePoint = codePointBefore(startIndex)
            val charCount = Character.charCount(codePoint)
            val nextIndex = startIndex - charCount
            val charBytes = substring(nextIndex, startIndex).byteSize()
            if (bytes + charBytes > maxBytes) break
            bytes += charBytes
            startIndex = nextIndex
        }
        return substring(startIndex)
    }

    // Safe decryption exposure helper for internal diagnostics use
    suspend fun readLogsDecrypted(): String =
        withContext(writeDispatcher) {
            flush(fromSchedule = false)
            readAllLogs()
        }

    companion object {
        const val DEFAULT_MAX_FILE_SIZE_BYTES: Long = 2L * 1024L * 1024L
        const val DEFAULT_MAX_BACKUPS: Int = 2

        internal fun sanitizeLogMessage(message: String): String {
            var sanitized = message
            
            // Redact UUIDs
            sanitized = sanitized.replace(
                Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", RegexOption.IGNORE_CASE),
                "***"
            )
            
            // Redact specific health metrics numbers
            sanitized = sanitized.replace(
                Regex("(?i)\\b(HR|HRV|BP)\\s*(?:is\\s*)?\\d+(?:\\.\\d+)?(?:/\\d+)?"),
                "$1 ***"
            )
            
            return sanitized
        }
    }
}
