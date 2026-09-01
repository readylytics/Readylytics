package app.readylytics.health.crashreport

import java.io.File

object DiagnosticLogFileExport {
    internal const val FILE_PREFIX = "readylytics_diagnostics"
    const val DIAGNOSTIC_FILE_NAME = "$FILE_PREFIX.txt"
    private const val DIAGNOSTIC_LOG_DIR = "diagnostic_logs"

    fun write(
        directory: File,
        text: String,
    ): File {
        val targetDir = File(directory, DIAGNOSTIC_LOG_DIR).apply { mkdirs() }
        val file = File(targetDir, DIAGNOSTIC_FILE_NAME)
        file.writeText(text)
        return file
    }

    fun pruneDiagnosticCache(directory: File) {
        val targetDir = File(directory, DIAGNOSTIC_LOG_DIR)
        if (!targetDir.exists()) return
        val kept = File(targetDir, DIAGNOSTIC_FILE_NAME)
        targetDir.listFiles()?.forEach { file ->
            if (file.isFile && file != kept) file.delete()
        }
    }
}
