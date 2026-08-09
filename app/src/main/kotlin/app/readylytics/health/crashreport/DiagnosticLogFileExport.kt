package app.readylytics.health.crashreport

import java.io.File

object DiagnosticLogFileExport {
    private const val FILE_PREFIX = "readylytics_diagnostics_"

    fun write(
        directory: File,
        text: String,
    ): File {
        val file = File.createTempFile(FILE_PREFIX, ".txt", directory)
        file.writeText(text)
        return file
    }
}
