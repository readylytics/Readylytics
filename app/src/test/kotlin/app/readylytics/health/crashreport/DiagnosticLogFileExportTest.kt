package app.readylytics.health.crashreport

import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagnosticLogFileExportTest {
    @Test
    fun `write creates file inside the declared diagnostic_logs subfolder`() {
        val cacheDir = Files.createTempDirectory("diag").toFile()
        try {
            val file = DiagnosticLogFileExport.write(cacheDir, "line1\nline2\n")

            assertTrue(file.exists())
            assertEquals("diagnostic_logs", file.parentFile.name)
            assertEquals(cacheDir, file.parentFile.parentFile)
            assertTrue(file.name.startsWith(DiagnosticLogFileExport.FILE_PREFIX))
            assertTrue(file.name.endsWith(".txt"))
            assertEquals("line1\nline2\n", file.readText())
        } finally {
            cacheDir.deleteRecursively()
        }
    }
}
