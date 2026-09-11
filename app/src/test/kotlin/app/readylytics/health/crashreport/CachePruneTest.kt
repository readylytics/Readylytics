package app.readylytics.health.crashreport

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CachePruneTest {
    private fun mockContextWithCacheDir(cacheDir: File): Context {
        val context = mockk<Context>()
        every { context.cacheDir } returns cacheDir
        return context
    }

    @Test
    fun `pruneCacheDirectories removes orphaned files from all three diagnostic dirs keeping canonical files`() {
        val cacheDir = Files.createTempDirectory("cache").toFile()
        try {
            val diagnosticDir = File(cacheDir, "diagnostic_logs").apply { mkdirs() }
            val canonicalDiagnostic = File(diagnosticDir, "readylytics_diagnostics.txt").apply { writeText("diag") }
            File(diagnosticDir, "readylytics_diagnostics_old.txt").apply { writeText("stale") }

            val crashDir = File(cacheDir, "crash_reports_v2").apply { mkdirs() }
            val canonicalCrash = File(crashDir, "latest_crash.txt").apply { writeText("crash") }
            File(crashDir, "latest_crash_old.txt").apply { writeText("stale") }

            val logcatDir = File(cacheDir, "logcat_capture").apply { mkdirs() }
            val canonicalLogcat = File(logcatDir, "logcat_capture.txt").apply { writeText("logcat") }
            File(logcatDir, "logcat_capture_old.txt").apply { writeText("stale") }

            CachePrune.pruneCacheDirectories(mockContextWithCacheDir(cacheDir))

            assertTrue(canonicalDiagnostic.exists())
            assertFalse(File(diagnosticDir, "readylytics_diagnostics_old.txt").exists())
            assertTrue(canonicalCrash.exists())
            assertFalse(File(crashDir, "latest_crash_old.txt").exists())
            assertTrue(canonicalLogcat.exists())
            assertFalse(File(logcatDir, "logcat_capture_old.txt").exists())
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun `pruneCacheDirectories is a no-op when cache dirs do not exist`() {
        val cacheDir = Files.createTempDirectory("cache").toFile()
        try {
            CachePrune.pruneCacheDirectories(mockContextWithCacheDir(cacheDir))
            assertFalse(File(cacheDir, "diagnostic_logs").exists())
            assertFalse(File(cacheDir, "crash_reports").exists())
            assertFalse(File(cacheDir, "crash_reports_v2").exists())
            assertFalse(File(cacheDir, "logs").exists())
            assertFalse(File(cacheDir, "logcat_capture").exists())
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun `pruneCacheDirectories retires legacy slots while preserving unrelated files`() {
        val cacheDir = Files.createTempDirectory("cache").toFile()
        try {
            val legacyLogs = File(cacheDir, "logs").apply { mkdirs() }
            val legacyLogFile = File(legacyLogs, "prod_logs.txt").apply { writeText("unsafe-log") }
            val legacyCrash = File(cacheDir, "crash_reports").apply { mkdirs() }
            val legacyCrashFile = File(legacyCrash, "latest_crash.txt").apply { writeText("unsafe-crash") }

            val unrelatedDir = File(cacheDir, "unrelated_dir").apply { mkdirs() }
            val unrelatedFile = File(unrelatedDir, "preserved.txt").apply { writeText("keep-me") }
            val unrelatedRootFile = File(cacheDir, "unrelated_root.txt").apply { writeText("keep-root") }

            CachePrune.pruneCacheDirectories(mockContextWithCacheDir(cacheDir))

            assertFalse(legacyLogFile.exists())
            assertFalse(legacyLogs.exists())
            assertFalse(legacyCrashFile.exists())
            assertFalse(legacyCrash.exists())

            assertTrue(unrelatedFile.exists())
            kotlin.test.assertEquals("keep-me", unrelatedFile.readText())
            assertTrue(unrelatedRootFile.exists())
            kotlin.test.assertEquals("keep-root", unrelatedRootFile.readText())
        } finally {
            cacheDir.deleteRecursively()
        }
    }
}
