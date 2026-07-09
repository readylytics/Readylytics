package app.readylytics.health.domain.util

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLogTest {
    private class TestLogSink : DomainLogSink {
        val logs = mutableListOf<String>()

        override fun log(
            level: LogLevel,
            tag: String,
            message: String,
            throwable: Throwable?,
            context: LogContext
        ) {
            logs.add("[$level] [$tag] [Session:${context.sessionId ?: "none"}] $message")
        }
    }

    @Test
    fun testScopedLoggerAttachesSessionId() {
        val sink = TestLogSink()
        DomainLogger.installSink(sink)

        DomainLogger.scoped(tag = "SyncTest", correlationId = "sync-1234") {
            info { "Beginning step 1" }
            warn(Exception("Failure")) { "Encountered step 1 delay" }
        }

        assertEquals(2, sink.logs.size)
        assertEquals("[INFO] [SyncTest] [Session:sync-1234] Beginning step 1", sink.logs[0])
        assertEquals("[WARN] [SyncTest] [Session:sync-1234] Encountered step 1 delay", sink.logs[1])
    }
}
