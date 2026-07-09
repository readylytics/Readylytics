package app.readylytics.health.util

import android.content.Context
import android.util.Log
import app.readylytics.health.domain.util.LogContext
import app.readylytics.health.domain.util.LogLevel
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SecureFileLogSinkTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var mockContext: Context
    private lateinit var cacheDir: File

    @Before
    fun setUp() {
        mockContext = mockk()
        cacheDir = tempFolder.newFolder("cache")
        every { mockContext.cacheDir } returns cacheDir

        // Mock android.util.Log to prevent RuntimeException during JVM tests
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any() as String) } returns 0
        every { Log.w(any(), any() as String, any()) } returns 0
        every { Log.e(any(), any() as String) } returns 0
        every { Log.e(any(), any() as String, any()) } returns 0
        every { Log.getStackTraceString(any()) } answers { firstArg<Throwable>().stackTraceToString() }
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun testFileRotationCreatesRotatedBackups() =
        runBlocking {
            // Setup a sink with a tiny max size (100 bytes) and Dispatchers.Unconfined to make writes synchronous
            val sink =
                SecureFileLogSink(
                    context = mockContext,
                    maxFileSize = 100L,
                    maxBackups = 2,
                    encryptStreams = false, // Disable encryption for testing raw content easily
                    coroutineContext = Dispatchers.Unconfined,
                )

            val longMessage = "This is a very long log message that will exceed the file limit quickly. "

            // Write multiple lines to trigger rotation (we need at least 15 logs to force multiple flushes and rotations)
            for (i in 1..15) {
                sink.log(LogLevel.INFO, "TestTag", "Log entry #$i: $longMessage", null, LogContext("session-1"))
            }

            // Force a read (which flushes any leftover logs)
            sink.readLogsDecrypted()

            val logFile = File(cacheDir, "logs/prod_logs.txt")
            val rotatedFile1 = File(cacheDir, "logs/prod_logs.txt.1")
            val rotatedFile2 = File(cacheDir, "logs/prod_logs.txt.2")

            assertTrue("Primary log file must exist", logFile.exists())
            assertTrue("First rotated log file must exist", rotatedFile1.exists())
            assertTrue("Second rotated log file must exist", rotatedFile2.exists())
        }

    @Test
    fun testPlainTextLogsWrittenCorrectly() =
        runBlocking {
            val sink =
                SecureFileLogSink(
                    context = mockContext,
                    maxFileSize = 10000L,
                    maxBackups = 2,
                    encryptStreams = false,
                    coroutineContext = Dispatchers.Unconfined,
                )

            val exception = RuntimeException("Test Exception")
            sink.log(LogLevel.ERROR, "ErrorTag", "Something went wrong", exception, LogContext("session-123"))

            val content = sink.readLogsDecrypted()
            assertTrue("Log should contain Tag", content.contains("[ErrorTag]"))
            assertTrue("Log should contain Level", content.contains("[ERROR]"))
            assertTrue("Log should contain SessionId", content.contains("[Session:session-123]"))
            assertTrue("Log should contain Message", content.contains("Something went wrong"))
            assertTrue("Log should contain Stack Trace", content.contains("java.lang.RuntimeException: Test Exception"))
        }

    @Test
    fun testReadLogsDecryptedReturnsEmptyWhenFileNotExists() =
        runBlocking {
            val sink =
                SecureFileLogSink(
                    context = mockContext,
                    encryptStreams = false,
                    coroutineContext = Dispatchers.Unconfined,
                )
            assertEquals("", sink.readLogsDecrypted())
        }

    @Test
    fun testBufferingBeforeFiveLogs() =
        runBlocking {
            val sink =
                SecureFileLogSink(
                    context = mockContext,
                    maxFileSize = 10000L,
                    maxBackups = 2,
                    encryptStreams = false,
                    coroutineContext = Dispatchers.Unconfined,
                )
            sink.log(LogLevel.INFO, "TestTag", "Log 1", null, LogContext("session-1"))

            // File should not exist or be empty because it hasn't flushed yet
            val logFile = File(cacheDir, "logs/prod_logs.txt")
            assertTrue(!logFile.exists() || logFile.readText().isEmpty())

            // readLogsDecrypted should force flush
            val content = sink.readLogsDecrypted()
            assertTrue(content.contains("Log 1"))
        }

    @Test
    fun testFlushOnFiveLogs() =
        runBlocking {
            val sink =
                SecureFileLogSink(
                    context = mockContext,
                    maxFileSize = 10000L,
                    maxBackups = 2,
                    encryptStreams = false,
                    coroutineContext = Dispatchers.Unconfined,
                )
            for (i in 1..5) {
                sink.log(LogLevel.INFO, "TestTag", "Log $i", null, LogContext("session-1"))
            }

            // File should exist and contain all 5 logs immediately without calling readLogsDecrypted
            val logFile = File(cacheDir, "logs/prod_logs.txt")
            assertTrue(logFile.exists())
            val content = logFile.readText()
            for (i in 1..5) {
                assertTrue(content.contains("Log $i"))
            }
        }

    @Test
    fun testRotationConcatenationChronological() =
        runBlocking {
            val sink =
                SecureFileLogSink(
                    context = mockContext,
                    maxFileSize = 10000L,
                    maxBackups = 2,
                    encryptStreams = false,
                    coroutineContext = Dispatchers.Unconfined,
                )

            val logDir = File(cacheDir, "logs")
            if (!logDir.exists()) logDir.mkdirs()

            // Write directly to backup files and active log file
            File(logDir, "prod_logs.txt.2").writeText("Oldest log\n")
            File(logDir, "prod_logs.txt.1").writeText("Middle log\n")
            File(logDir, "prod_logs.txt").writeText("Newest log\n")

            val content = sink.readLogsDecrypted()
            val expected = "Oldest log\nMiddle log\nNewest log\n"
            assertEquals(expected, content)
        }

    @Test
    fun testRotationConcatenationSomeMissing() =
        runBlocking {
            val sink =
                SecureFileLogSink(
                    context = mockContext,
                    maxFileSize = 10000L,
                    maxBackups = 2,
                    encryptStreams = false,
                    coroutineContext = Dispatchers.Unconfined,
                )

            val logDir = File(cacheDir, "logs")
            if (!logDir.exists()) logDir.mkdirs()

            // Write directly to prod_logs.txt.2 and prod_logs.txt, skip prod_logs.txt.1
            File(logDir, "prod_logs.txt.2").writeText("Oldest log\n")
            File(logDir, "prod_logs.txt").writeText("Newest log\n")

            val content = sink.readLogsDecrypted()
            val expected = "Oldest log\nNewest log\n"
            assertEquals(expected, content)
        }

    @Test
    fun testFlushAfterTwoSeconds() =
        runBlocking {
            val sink =
                SecureFileLogSink(
                    context = mockContext,
                    maxFileSize = 10000L,
                    maxBackups = 2,
                    encryptStreams = false,
                    coroutineContext = Dispatchers.IO,
                )

            sink.log(LogLevel.INFO, "TestTag", "Timed Log", null, LogContext("session-1"))

            // Initially, it shouldn't be written to disk immediately
            val logFile = File(cacheDir, "logs/prod_logs.txt")
            delay(200)
            assertTrue(!logFile.exists() || logFile.readText().isEmpty())

            // Wait for 2.2 seconds (total 2400ms) to let the flush schedule execute
            delay(2200)
            assertTrue(logFile.exists())
            assertTrue(logFile.readText().contains("Timed Log"))
        }
}
