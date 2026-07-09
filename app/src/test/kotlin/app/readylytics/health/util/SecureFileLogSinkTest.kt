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
    fun testFileRotationCreatesRotatedBackups() {
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

        // Write multiple lines to trigger rotation
        for (i in 1..10) {
            sink.log(LogLevel.INFO, "TestTag", "Log entry #$i: $longMessage", null, LogContext("session-1"))
        }

        val logFile = File(cacheDir, "logs/prod_logs.txt")
        val rotatedFile1 = File(cacheDir, "logs/prod_logs.txt.1")
        val rotatedFile2 = File(cacheDir, "logs/prod_logs.txt.2")

        assertTrue("Primary log file must exist", logFile.exists())
        assertTrue("First rotated log file must exist", rotatedFile1.exists())
        assertTrue("Second rotated log file must exist", rotatedFile2.exists())
    }

    @Test
    fun testPlainTextLogsWrittenCorrectly() {
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
    fun testReadLogsDecryptedReturnsEmptyWhenFileNotExists() {
        val sink =
            SecureFileLogSink(
                context = mockContext,
                encryptStreams = false,
                coroutineContext = Dispatchers.Unconfined,
            )
        assertEquals("", sink.readLogsDecrypted())
    }
}
