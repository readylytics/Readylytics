package app.readylytics.health.util

import android.content.Context
import android.util.Log
import app.readylytics.health.core.model.domain.util.DomainLogSink
import app.readylytics.health.core.model.domain.util.DomainLogger
import app.readylytics.health.core.model.domain.util.LogContext
import app.readylytics.health.core.model.domain.util.LogLevel
import app.readylytics.health.core.model.domain.util.logD
import app.readylytics.health.core.model.domain.util.logI
import app.readylytics.health.data.security.SecureFileStore
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
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any() as String) } returns 0
        every { Log.w(any(), any() as String, any()) } returns 0
        every { Log.e(any(), any() as String) } returns 0
        every { Log.e(any(), any() as String, any()) } returns 0
        every { Log.println(any(), any(), any()) } returns 0
        every { Log.getStackTraceString(any()) } answers { firstArg<Throwable>().stackTraceToString() }
    }

    @After
    fun tearDown() {
        // testDebugIsNotLoggableAndNeverReachesTheFile (and any future test in this class) may
        // install a SecureFileLogSink backed by this test's TemporaryFolder into the
        // process-wide DomainLogger singleton. Gradle reuses one JVM across test classes, so a
        // stale sink left installed would outlive the deleted TemporaryFolder and swallow
        // IOExceptions for every later test in the module that logs without installing its own
        // sink. Always restore a no-op sink, even if the test body failed.
        DomainLogger.installSink(
            object : DomainLogSink {
                override fun log(
                    level: LogLevel,
                    tag: String,
                    message: String,
                    throwable: Throwable?,
                    context: LogContext,
                ) = Unit
            },
        )
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

            val storedContents = secureFileStore.storedContents(File(cacheDir, SecureFileLogSink.LOG_DIRECTORY_NAME))
            val totalBytes = storedContents.values.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() }

            assertTrue("Rotation should never exceed active plus backup slots", storedContents.size <= 3)
            assertTrue(
                "Lines are never split: every slot ends on a line boundary",
                storedContents.values.all { it.endsWith("\n") },
            )
            // A single line larger than a slot occupies its own slot, so the bound is per-line,
            // not per-byte. Total retention is structural: (maxBackups + 1) slots.
            assertTrue("Total retention must stay bounded", totalBytes <= 3 * (40L + longestLineBytes(storedContents)))
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
            assertTrue("Log should contain reason", content.contains("OPERATION_FAILED"))
            assertTrue("Log should contain Stack Trace", content.contains("java.lang.RuntimeException"))
            assertFalse("Log must not contain Tag", content.contains("[ErrorTag]"))
            assertFalse("Log must not contain SessionId", content.contains("session-123"))
            assertFalse("Log must not contain Message", content.contains("Something went wrong"))
            assertFalse("Log must not contain Exception Message", content.contains("Test Exception"))
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
                    flushLineThreshold = 5,
                )
            sink.log(LogLevel.INFO, "TestTag", "Log 1", null, LogContext("session-1"))

            val logFile = File(cacheDir, "${SecureFileLogSink.LOG_DIRECTORY_NAME}/prod_logs.txt")
            assertTrue(!logFile.exists() || logFile.readText().isEmpty())

            val content = sink.readLogsDecrypted()
            assertTrue(content.contains("OPERATION_FAILED"))
        }

    @Test
    fun testEncryptedModeDelegatesToSecureFileStore() =
        runBlocking {
            val secureFileStore = FakeSecureFileStore()

            fun newSink() =
                SecureFileLogSink(
                    context = mockContext,
                    maxFileSize = 10000L,
                    maxBackups = 2,
                    encryptStreams = true,
                    coroutineContext = Dispatchers.Unconfined,
                    secureFileStore = secureFileStore,
                )

            val sink = newSink()
            for (i in 1..5) {
                sink.log(LogLevel.INFO, "TestTag", "Encrypted log $i", null, LogContext("session-1"))
            }
            sink.readLogsDecrypted()

            assertTrue("Encrypted write should delegate to helper", secureFileStore.writeCalls.isNotEmpty())
            assertEquals("prod_logs.txt", secureFileStore.writeCalls.last())

            // The first instance hydrates from an active slot that doesn't exist yet, so it never
            // issues a read (LogSlotStore hydrates once and never re-reads its own in-memory active
            // buffer). A fresh instance hydrating the slot just written proves the read path also
            // delegates to the helper.
            val content = newSink().readLogsDecrypted()
            assertTrue(content.contains("OPERATION_FAILED"))
            for (i in 1..5) {
                assertFalse(content.contains("Encrypted log $i"))
            }
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
                    flushLineThreshold = 5,
                )
            for (i in 1..5) {
                sink.log(LogLevel.INFO, "TestTag", "Log $i", null, LogContext("session-1"))
            }

            val logFile = File(cacheDir, "${SecureFileLogSink.LOG_DIRECTORY_NAME}/prod_logs.txt")
            assertTrue(logFile.exists())
            val content = logFile.readText()
            assertTrue(content.contains("OPERATION_FAILED"))
            for (i in 1..5) {
                assertFalse(content.contains("Log $i"))
            }
        }

    @Test
    fun testUnreadableOldEncryptedContentTreatedAsEmptyAndRewritten() =
        runBlocking {
            val secureFileStore = FakeSecureFileStore()
            val legacyFile =
                File(cacheDir, "${SecureFileLogSink.LOG_DIRECTORY_NAME}/prod_logs.txt").apply {
                    parentFile?.mkdirs()
                    writeText("legacy-garbage")
                }

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

            assertTrue(content.contains("OPERATION_FAILED"))
            for (i in 1..5) {
                assertFalse(content.contains("Fresh log $i"))
            }
            assertTrue(!content.contains("legacy-garbage"))
            assertTrue(
                "Unreadable content should be replaced with new readable data",
                secureFileStore.readableContentFor(legacyFile).contains("OPERATION_FAILED"),
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

            val logDir = File(cacheDir, SecureFileLogSink.LOG_DIRECTORY_NAME)
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

            val logDir = File(cacheDir, SecureFileLogSink.LOG_DIRECTORY_NAME)
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

            assertTrue(content.contains("OPERATION_FAILED"))
            assertTrue(content.contains("java.lang.RuntimeException"))
            assertFalse(content.contains("[ErrorTag]"))
            assertFalse(content.contains("[ERROR]"))
            assertFalse(content.contains("[Session:session-123]"))
            assertFalse(content.contains("Something went wrong"))
            assertFalse(content.contains("Test Exception"))
            assertTrue(
                "Plaintext mode should not use secure file helper",
                secureFileStore.writeCalls.isEmpty() && secureFileStore.readCalls.isEmpty(),
            )
        }

    @Test
    fun testFlushAfterInterval() =
        runBlocking {
            val sink =
                SecureFileLogSink(
                    context = mockContext,
                    maxFileSize = 10000L,
                    maxBackups = 2,
                    encryptStreams = false,
                    coroutineContext = Dispatchers.IO,
                    flushLineThreshold = 64,
                    flushIntervalMs = 300L,
                )

            sink.log(LogLevel.INFO, "TestTag", "Timed Log", null, LogContext("session-1"))

            val logFile = File(cacheDir, "${SecureFileLogSink.LOG_DIRECTORY_NAME}/prod_logs.txt")
            delay(50)
            assertTrue(!logFile.exists() || logFile.readText().isEmpty())

            delay(500)
            assertTrue(logFile.exists())
            val content = logFile.readText()
            assertTrue(content.contains("OPERATION_FAILED"))
            assertFalse(content.contains("Timed Log"))
        }

    @Test
    fun testFlushWritesOnlyTheActiveSlot() =
        runBlocking {
            val secureFileStore = FakeSecureFileStore()
            val sink =
                SecureFileLogSink(
                    context = mockContext,
                    maxFileSize = 10_000L,
                    maxBackups = 2,
                    encryptStreams = true,
                    coroutineContext = Dispatchers.Unconfined,
                    secureFileStore = secureFileStore,
                    flushLineThreshold = 2,
                )

            for (i in 1..6) {
                sink.log(LogLevel.INFO, "TestTag", "Log $i", null, LogContext("session-1"))
            }

            assertEquals(
                "A flush must never touch backup slots",
                listOf("prod_logs.txt"),
                secureFileStore.writeCalls.distinct(),
            )
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
            val slots = secureFileStore.storedContents(File(cacheDir, SecureFileLogSink.LOG_DIRECTORY_NAME))
            val totalBytes = slots.values.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() }

            assertTrue(content.contains("OPERATION_FAILED"))
            assertFalse(content.contains("Log 20"))
            assertTrue("Slot count is structurally bounded", slots.size <= 2)
            assertTrue("Total retention must stay bounded", totalBytes <= 2 * (80L + longestLineBytes(slots)))
        }

    @Test
    fun testLogSanitization() {
        val original = "User HR is 120 bpm, HRV 45.2, BP 120/80"
        val sanitized = SecureFileLogSink.sanitizeLogMessage(original)

        assertFalse("Should redact heart rate", sanitized.contains("120"))
        assertFalse("Should redact HRV", sanitized.contains("45.2"))
        assertTrue("Should contain redaction markers", sanitized.contains("***"))
    }

    @Test
    fun testLogSanitizationHandlesSeparatorVariants() {
        val original = "HR=120, HR:118, BPM 150"
        val sanitized = SecureFileLogSink.sanitizeLogMessage(original)

        assertFalse("Should redact HR=120", sanitized.contains("120"))
        assertFalse("Should redact HR:118", sanitized.contains("118"))
        assertFalse("Should redact BPM 150", sanitized.contains("150"))
    }

    @Test
    fun testStackTraceRedactsHealthMetrics() =
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

            val exception = RuntimeException("Invalid reading: HR is 245")
            sink.log(LogLevel.ERROR, "ErrorTag", "Validation failed", exception, LogContext("session-1"))

            val content = sink.readLogsDecrypted()
            assertFalse("Stack trace text should be redacted too", content.contains("245"))
        }

    @Test
    fun testReleaseDiagnosticsOmitNestedPayloadsFromLogcatAndDecryptedFile() =
        runBlocking {
            val loggedTags = mutableListOf<String>()
            val loggedMessages = mutableListOf<String>()
            setupMockLog(loggedTags, loggedMessages)

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

            val corpus =
                listOf(
                    "bpm=187",
                    "rmssd=94.7",
                    "52.5200,13.4050",
                    "source_private_123",
                    "content://private/tree/secret",
                    "backup-secret",
                )
            val root = IllegalStateException(corpus[5], IllegalArgumentException(corpus[0]))
            root.addSuppressed(java.io.IOException(corpus[1]))

            sink.log(
                level = LogLevel.ERROR,
                tag = corpus[3],
                message = corpus[2],
                throwable = root,
                context = LogContext(sessionId = corpus[4]),
            )

            val content = sink.readLogsDecrypted()

            corpus.forEach { item ->
                assertFalse("Decrypted log must not contain $item", content.contains(item))
                loggedTags.forEach { tag ->
                    assertFalse("Logcat tag must not contain $item", tag.contains(item))
                }
                loggedMessages.forEach { msg ->
                    assertFalse("Logcat message must not contain $item", msg.contains(item))
                }
            }
        }

    private fun setupMockLog(
        loggedTags: MutableList<String>,
        loggedMessages: MutableList<String>,
    ) {
        every { Log.d(any(), any()) } answers {
            loggedTags += firstArg<String>()
            loggedMessages += secondArg<String>()
            0
        }
        every { Log.i(any(), any()) } answers {
            loggedTags += firstArg<String>()
            loggedMessages += secondArg<String>()
            0
        }
        every { Log.w(any(), any() as String) } answers {
            loggedTags += firstArg<String>()
            loggedMessages += secondArg<String>()
            0
        }
        every { Log.w(any(), any() as String, any()) } answers {
            loggedTags += firstArg<String>()
            loggedMessages += secondArg<String>()
            loggedMessages += thirdArg<Throwable?>()?.message.orEmpty()
            0
        }
        every { Log.e(any(), any() as String) } answers {
            loggedTags += firstArg<String>()
            loggedMessages += secondArg<String>()
            0
        }
        every { Log.e(any(), any() as String, any()) } answers {
            loggedTags += firstArg<String>()
            loggedMessages += secondArg<String>()
            loggedMessages += thirdArg<Throwable?>()?.message.orEmpty()
            0
        }
        every { Log.println(any(), any(), any()) } answers {
            loggedTags += secondArg<String>()
            loggedMessages += thirdArg<String>()
            0
        }
    }

    @Test
    fun testDebugIsNotLoggableAndNeverReachesTheFile() =
        runBlocking {
            val sink =
                SecureFileLogSink(
                    context = mockContext,
                    maxFileSize = 10_000L,
                    maxBackups = 2,
                    encryptStreams = false,
                    coroutineContext = Dispatchers.Unconfined,
                )

            assertFalse("Release sink must drop DEBUG", sink.isLoggable(LogLevel.DEBUG, "TestTag"))
            assertTrue("INFO must still be persisted", sink.isLoggable(LogLevel.INFO, "TestTag"))

            DomainLogger.installSink(sink)
            logD("TestTag") { "chatty sync detail" }
            logI("TestTag") { "sync milestone" }

            val content = sink.readLogsDecrypted()
            assertFalse("DEBUG must not reach the diagnostic file", content.contains("chatty sync detail"))
            assertTrue("OPERATION_FAILED must reach the diagnostic file", content.contains("OPERATION_FAILED"))
            assertFalse("Raw message must not reach the diagnostic file", content.contains("sync milestone"))
        }

    private fun longestLineBytes(storedContents: Map<String, String>): Long =
        storedContents.values
            .flatMap { it.split("\n") }
            .maxOfOrNull { it.toByteArray(Charsets.UTF_8).size.toLong() + 1L } ?: 0L

    /**
     * Content is keyed by a per-write token that is written into the on-disk placeholder, so it
     * travels with the bytes when [LogSlotStore] renames a slot — exactly like real ciphertext.
     * Keying by filename does NOT work here: the store always writes the active slot under the same
     * name and renames it away afterwards, so two seals with no read in between would collide on a
     * single map entry and the older slot's content would vanish from the fake while its file is
     * still on disk. (The whole point of F2 is that nothing reads between seals.) Same scheme as
     * `LogSlotStoreTest.AdAwareSecureFileStore`.
     */
    private class FakeSecureFileStore : SecureFileStore {
        private val entries = linkedMapOf<String, String>()
        private var nextToken = 0
        val readCalls = mutableListOf<String>()
        val writeCalls = mutableListOf<String>()

        override fun readText(
            file: File,
            associatedData: ByteArray,
        ): String {
            readCalls += file.name
            val token = tokenOf(file) ?: return ""
            return entries[token].orEmpty()
        }

        override fun writeText(
            file: File,
            content: String,
            associatedData: ByteArray,
        ) {
            writeCalls += file.name
            val token = "t${nextToken++}"
            entries[token] = content
            file.parentFile?.mkdirs()
            file.writeText("$CIPHERTEXT_PREFIX$token")
        }

        fun readableContentFor(file: File): String = tokenOf(file)?.let { entries[it] }.orEmpty()

        /** Plaintext of every slot currently present in [directory], keyed by its current filename. */
        fun storedContents(directory: File): Map<String, String> =
            directory
                .listFiles()
                .orEmpty()
                .sortedBy { it.name }
                .mapNotNull { file -> tokenOf(file)?.let { entries[it] }?.let { file.name to it } }
                .toMap()

        private fun tokenOf(file: File): String? =
            if (file.exists()) file.readText().removePrefix(CIPHERTEXT_PREFIX) else null

        private companion object {
            const val CIPHERTEXT_PREFIX = "ciphertext:"
        }
    }
}
