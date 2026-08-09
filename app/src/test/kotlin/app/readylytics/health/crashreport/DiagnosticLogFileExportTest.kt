package app.readylytics.health.crashreport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticLogFileExportTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `write creates diagnostic text file in supplied directory`() {
        val directory = tempFolder.newFolder("cache")

        val file = DiagnosticLogFileExport.write(directory, "redacted log")

        assertTrue(file.name.startsWith("readylytics_diagnostics_"))
        assertTrue(file.name.endsWith(".txt"))
        assertEquals("redacted log", file.readText())
        assertEquals(directory.canonicalPath, file.parentFile?.canonicalPath)
    }
}
