package app.readylytics.health.crashreport

import android.content.Context
import app.readylytics.health.data.crashreport.CrashReportStoreImpl
import java.io.File

/**
 * Startup cache hygiene (R2-SEC-001, WP-02): the app writes plaintext diagnostics into cache
 * subdirectories. Each directory is bounded to a single canonical file on every write path, but a
 * defensive startup prune keeps them from ever accumulating orphaned files (e.g. from an older
 * version that used `createTempFile`). Only the canonical file of each directory is kept.
 * Legacy unsafe log and crash slots are retired and deleted on startup.
 */
object CachePrune {
    private const val LEGACY_LOGS_DIR = "logs"
    private const val LEGACY_CRASH_REPORTS_DIR = "crash_reports"
    private const val DIAGNOSTIC_LOGS_DIR = "diagnostic_logs"
    private const val CANONICAL_DIAGNOSTIC_FILE = "readylytics_diagnostics.txt"
    private const val LOGCAT_CAPTURE_DIR = "logcat_capture"
    private const val CANONICAL_LOGCAT_FILE = "logcat_capture.txt"

    fun pruneCacheDirectories(context: Context) {
        retireLegacySlots(context)
        pruneDirectory(File(File(context.cacheDir, DIAGNOSTIC_LOGS_DIR), CANONICAL_DIAGNOSTIC_FILE))
        pruneDirectory(
            File(
                File(context.cacheDir, CrashReportStoreImpl.CRASH_REPORTS_DIR),
                CrashReportStoreImpl.LATEST_CRASH_FILE,
            ),
        )
        pruneDirectory(File(File(context.cacheDir, LOGCAT_CAPTURE_DIR), CANONICAL_LOGCAT_FILE))
    }

    private fun retireLegacySlots(context: Context) {
        val legacyLogs = File(context.cacheDir, LEGACY_LOGS_DIR)
        if (legacyLogs.exists()) {
            legacyLogs.deleteRecursively()
        }
        val legacyCrashReports = File(context.cacheDir, LEGACY_CRASH_REPORTS_DIR)
        if (legacyCrashReports.exists()) {
            legacyCrashReports.deleteRecursively()
        }
    }

    private fun pruneDirectory(canonicalFile: File) {
        val dir = canonicalFile.parentFile ?: return
        if (!dir.exists()) return
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file != canonicalFile) file.delete()
        }
    }
}
