package app.readylytics.health.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureFileLogSinkTest {
    @Test
    fun testLogSanitization() {
        val original = "User HR is 120 bpm, HRV 45.2, BP 120/80"
        val sanitized = SecureFileLogSink.sanitizeLogMessage(original)

        assertFalse("Should redact heart rate", sanitized.contains("120"))
        assertFalse("Should redact HRV", sanitized.contains("45.2"))
        assertTrue("Should contain redaction markers", sanitized.contains("***"))
    }
}
