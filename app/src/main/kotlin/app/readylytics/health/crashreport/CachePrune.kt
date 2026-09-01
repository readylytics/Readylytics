package app.readylytics.health.crashreport

import android.content.Context
import java.io.File

/**
 * Startup cache hygiene (R2-SEC-001): the app writes plaintext diagnostics into three cache
 * subdirectories. Each directory is bounded to a single canonical file on every write path, but a
 * defensive startup prune keeps them from ever accumulating orphaned files (e.g. from an older
 * version that used `createTempFile`). Only the canonical file of each directory is kept.
 */
object CachePrune {
    fun pruneCacheDirectories(context: Context) {
        pruneDirectory(File(File(context.cacheDir, "diagnostic_logs"), "readylytics_diagnostics.txt"))
        pruneDirectory(File(File(context.cacheDir, "crash_reports"), "latest_crash.txt"))
        pruneDirectory(File(File(context.cacheDir, "logcat_capture"), "logcat_capture.txt"))
    }

    private fun pruneDirectory(canonicalFile: File) {
        val dir = canonicalFile.parentFile ?: return
        if (!dir.exists()) return
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file != canonicalFile) file.delete()
        }
    }
}
