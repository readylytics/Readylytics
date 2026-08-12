package app.readylytics.health.crashreport

import java.io.File

object DiagnosticLogFileExport {
    internal const val FILE_PREFIX = "readylytics_diagnostics_"
    private const val DIAGNOSTIC_LOG_DIR = "diagnostic_logs"

    fun write(
        directory: File,
        text: String,
    ): File {
        val targetDir = File(directory, DIAGNOSTIC_LOG_DIR).apply { mkdirs() }
        val file = File.createTempFile(FILE_PREFIX, ".txt", targetDir)
        file.writeText(text)
        return file
    }
}
