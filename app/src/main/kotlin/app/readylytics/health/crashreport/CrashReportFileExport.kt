package app.readylytics.health.crashreport

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Writes an oversized crash/bug report to a user-picked SAF uri, for attaching to a GitHub issue
// manually once the report no longer fits in the issue-URL body. Mirrors LocalBackupManager's
// filename and contentResolver.openOutputStream write pattern.
object CrashReportFileExport {
    private val FILENAME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss-SSS")

    fun suggestedFilename(prefix: String): String {
        val timestamp = Instant.now().atZone(ZoneId.systemDefault()).format(FILENAME_FORMATTER)
        return "${prefix}_$timestamp.txt"
    }

    fun writeReport(
        context: Context,
        uri: Uri,
        text: String,
    ): Result<String> =
        try {
            context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                ?: return Result.failure(IllegalStateException("Could not open output stream for $uri"))
            Result.success(DocumentFile.fromSingleUri(context, uri)?.name ?: uri.lastPathSegment.orEmpty())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
}
