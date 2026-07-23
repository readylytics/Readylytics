package app.readylytics.health.util

import android.content.Context
import android.util.Log
import app.readylytics.health.data.security.SecureFileStore
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
import org.junit.Assert.assertFalse
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
            val secureFileStore = FakeSecureFileStore()
            val sink =
                SecureFileLogSink(
                    context = mockContext,
                    maxFileSize = 40L,
                    maxBackups = 2,
                    encryptStreams = true,
                    coroutineContext = Dispatchers.Unconfined,
                    secureFileStore = secureFileStore,
                )

            val longMessage = "This is a very long log message that will exceed the file limit quickly. "

            for (i in 1..15) {
                sink.log(LogLevel.INFO, "TestTag", "Log entry #$i: $longMessage", null, LogContext("session-1"))
            }

            sink.readLogsDecrypted()

            val storedContents = secureFileStore.storedContents()
            val totalBytes = storedContents.values.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() }

            assertTrue("Rotation should never exceed active plus backup slots", storedContents.size <= 3)
            assertTrue(
                "Each produced slot must stay within configured bound",
                storedContents.values.all { it.toByteArray(Charsets.UTF_8).size <= 40 },
            )
            assertTrue("Total retention must stay within configured bound", totalBytes <= 120L)
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

            val logFile = File(cacheDir, "logs/prod_logs.txt")
            assertTrue(!logFile.exists() || logFile.readText().isEmpty())

            val content = sink.readLogsDecrypted()
            assertTrue(content.contains("Log 1"))
        }

    @Test
    fun testEncryptedModeDelegatesToSecureFileStore() =
        runBlocking {
            val secureFileStore = FakeSecureFileStore()
            val sink =
                SecureFileLogSink(
                    context = mockContext,
                    maxFileSize = 10000L,
                    maxBackups = 2,
                    encryptStreams = true,
                    coroutineContext = Dispatchers.Unconfined,
                    secureFileStore = secureFileStore,
                )

            for (i in 1..5) {
                sink.log(LogLevel.INFO, "TestTag", "Encrypted log $i", null, LogContext("session-1"))
            }

            val content = sink.readLogsDecrypted()

            for (i in 1..5) {
                assertTrue(content.contains("Encrypted log $i"))
            }
            assertTrue("Encrypted write should delegate to helper", secureFileStore.writeCalls.isNotEmpty())
            assertEquals("prod_logs.txt", secureFileStore.writeCalls.last())
            assertTrue("Encrypted read should delegate to helper", secureFileStore.readCalls.contains("prod_logs.txt"))
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

            val logFile = File(cacheDir, "logs/prod_logs.txt")
            assertTrue(logFile.exists())
            val content = logFile.readText()
            for (i in 1..5) {
                assertTrue(content.contains("Log $i"))
            }
        }

    @Test
    fun testUnreadableOldEncryptedContentTreatedAsEmptyAndRewritten() =
        runBlocking {
            val secureFileStore = FakeSecureFileStore()
            val legacyFile =
                File(cacheDir, "logs/prod_logs.txt").apply {
                    parentFile?.mkdirs()
                    writeText("legacy-garbage")
                }
            secureFileStore.stubUnreadable(legacyFile)

            val sink =
                SecureFileLogSink(
                    context = mockContext,
                    maxFileSize = 10000L,
                    maxBackups = 2,
                    encryptStreams = true,
                    coroutineContext = Dispatchers.Unconfined,
                    secureFileStore = secureFileStore,
                )

            for (i in 1..5) {
                sink.log(LogLevel.INFO, "TestTag", "Fresh log $i", null, LogContext("session-1"))
            }

            val content = sink.readLogsDecrypted()

            for (i in 1..5) {
                assertTrue(content.contains("Fresh log $i"))
            }
            assertTrue(!content.contains("legacy-garbage"))
            assertTrue(
                "Unreadable content should be replaced with new readable data",
                secureFileStore.readableContentFor(legacyFile).contains("Fresh log 5"),
            )
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

            File(logDir, "prod_logs.txt.2").writeText("Oldest log\n")
            File(logDir, "prod_logs.txt.1").writeText("Middle log\n")
            File(logDir, "prod_logs.txt").writeText("Newest log\n")

            val content = sink.readLogsDecrypted()
            assertEquals("Oldest log\nMiddle log\nNewest log\n", content)
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

            File(logDir, "prod_logs.txt.2").writeText("Oldest log\n")
            File(logDir, "prod_logs.txt").writeText("Newest log\n")

            val content = sink.readLogsDecrypted()
            assertEquals("Oldest log\nNewest log\n", content)
        }

    @Test
    fun testPlainTextModeStillWorksWithoutSecureFileStore() =
        runBlocking {
            val secureFileStore = FakeSecureFileStore()
            val sink =
                SecureFileLogSink(
                    context = mockContext,
                    maxFileSize = 10000L,
                    maxBackups = 2,
                    encryptStreams = false,
                    coroutineContext = Dispatchers.Unconfined,
                    secureFileStore = secureFileStore,
                )

            val exception = RuntimeException("Test Exception")
            sink.log(LogLevel.ERROR, "ErrorTag", "Something went wrong", exception, LogContext("session-123"))

            val content = sink.readLogsDecrypted()

            assertTrue(content.contains("[ErrorTag]"))
            assertTrue(content.contains("[ERROR]"))
            assertTrue(content.contains("[Session:session-123]"))
            assertTrue(content.contains("Something went wrong"))
            assertTrue(content.contains("java.lang.RuntimeException: Test Exception"))
            assertTrue(
                "Plaintext mode should not use secure file helper",
                secureFileStore.writeCalls.isEmpty() && secureFileStore.readCalls.isEmpty(),
            )
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

    @Test
    fun testTotalRetentionStaysWithinConfiguredBound() =
        runBlocking {
            val secureFileStore = FakeSecureFileStore()
            val sink =
                SecureFileLogSink(
                    context = mockContext,
                    maxFileSize = 80L,
                    maxBackups = 1,
                    encryptStreams = true,
                    coroutineContext = Dispatchers.Unconfined,
                    secureFileStore = secureFileStore,
                )

            for (i in 1..20) {
                sink.log(LogLevel.INFO, "TestTag", "Log $i " + "x".repeat(40), null, LogContext("session-1"))
            }

            val content = sink.readLogsDecrypted()
            val totalBytes =
                secureFileStore.storedContents().values.sumOf {
                    it
                        .toByteArray(
                            Charsets.UTF_8,
                        ).size
                        .toLong()
                }

            assertTrue(content.contains("Log 20"))
            assertTrue(totalBytes <= 160L)
        }

    @Test
    fun testLogSanitization() {
        val original = "User HR is 120 bpm, HRV 45.2, BP 120/80"
        val sanitized = SecureFileLogSink.sanitizeLogMessage(original)

        assertFalse("Should redact heart rate", sanitized.contains("120"))
        assertFalse("Should redact HRV", sanitized.contains("45.2"))
        assertTrue("Should contain redaction markers", sanitized.contains("***"))
    }

    private class FakeSecureFileStore : SecureFileStore {
        private val storedContents = linkedMapOf<String, String>()
        private val unreadableFiles = mutableSetOf<String>()
        val readCalls = mutableListOf<String>()
        val writeCalls = mutableListOf<String>()

        override fun readText(
            file: File,
            associatedData: ByteArray,
        ): String {
            val key = keyFor(file)
            readCalls += key
            return if (key in unreadableFiles) "" else storedContents[key].orEmpty()
        }

        override fun writeText(
            file: File,
            content: String,
            associatedData: ByteArray,
        ) {
            val key = keyFor(file)
            writeCalls += key
            unreadableFiles.remove(key)
            storedContents[key] = content
            file.parentFile?.mkdirs()
            file.writeText("ciphertext:$key")
        }

        fun stubUnreadable(file: File) {
            unreadableFiles += keyFor(file)
        }

        fun readableContentFor(file: File): String = storedContents[keyFor(file)].orEmpty()

        fun storedContents(): Map<String, String> = storedContents.toMap()

        private fun keyFor(file: File): String = file.name
    }
}
