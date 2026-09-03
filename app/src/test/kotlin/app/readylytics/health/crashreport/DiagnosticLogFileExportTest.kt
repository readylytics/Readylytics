package app.readylytics.health.crashreport

import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticLogFileExportTest {
    @Test
    fun `write creates file inside the declared diagnostic_logs subfolder`() {
        val cacheDir = Files.createTempDirectory("diag").toFile()
        try {
            val file = DiagnosticLogFileExport.write(cacheDir, "line1\nline2\n")
            val diagnosticDir = requireNotNull(file.parentFile)
            val diagnosticRoot = requireNotNull(diagnosticDir.parentFile)

            assertTrue(file.exists())
            assertEquals("diagnostic_logs", diagnosticDir.name)
            assertEquals(cacheDir, diagnosticRoot)
            assertTrue(file.name.startsWith(DiagnosticLogFileExport.FILE_PREFIX))
            assertTrue(file.name.endsWith(".txt"))
            assertEquals("line1\nline2\n", file.readText())
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun `write overwrites single diagnostic file avoiding unbounded accumulation`() {
        val cacheDir = Files.createTempDirectory("diag").toFile()
        try {
            val file1 = DiagnosticLogFileExport.write(cacheDir, "first export")
            val file2 = DiagnosticLogFileExport.write(cacheDir, "second export")
            assertEquals(file1.absolutePath, file2.absolutePath)
            assertEquals("second export", file1.readText())
            val files = File(cacheDir, "diagnostic_logs").listFiles()
            assertEquals(1, files?.size)
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun `pruneDiagnosticCache removes stale diagnostic files keeping the active export`() {
        val cacheDir = Files.createTempDirectory("diag").toFile()
        try {
            val active = DiagnosticLogFileExport.write(cacheDir, "current")
            val dir = active.parentFile
            val stale = File(dir, "readylytics_diagnostics_old.txt")
            stale.writeText("stale")
            val other = File(dir, "unrelated.txt")
            other.writeText("other")

            DiagnosticLogFileExport.pruneDiagnosticCache(cacheDir)

            assertTrue(active.exists())
            assertFalse(stale.exists())
            assertFalse(other.exists())
        } finally {
            cacheDir.deleteRecursively()
        }
    }
}
