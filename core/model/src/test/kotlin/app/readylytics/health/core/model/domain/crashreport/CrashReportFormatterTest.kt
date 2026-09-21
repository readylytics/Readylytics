package app.readylytics.health.core.model.domain.crashreport

import org.junit.Test
import java.io.IOException
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrashReportFormatterTest {
    private val metadata =
        CrashReportMetadata(
            timestampIso = "2026-07-07T12:00:00Z",
            appVersionName = "1.2.3",
            appVersionCode = 45,
            androidRelease = "14",
            androidSdkInt = 34,
            deviceManufacturer = "Google",
            deviceModel = "Pixel 9",
        )

    @Test
    fun `includes stack trace and diagnostic metadata while omitting raw exception message`() {
        val throwable = IllegalStateException("boom")

        val report = formatCrashReport(throwable, metadata)

        assertContains(report, "IllegalStateException")
        assertFalse(report.contains("boom"))
        assertContains(report, "1.2.3 (45)")
        assertContains(report, "14 (SDK 34)")
        assertContains(report, "Google Pixel 9")
        assertContains(report, "2026-07-07T12:00:00Z")
    }

    @Test
    fun `crash report omits nested private payloads from messages`() {
        val corpus =
            listOf(
                "bpm=187",
                "rmssd=94.7",
                "52.5200,13.4050",
                "source_private_123",
                "content://private/tree/secret",
                "backup-secret",
            )
        val root = IllegalStateException(corpus[0], IllegalArgumentException(corpus[1]))
        root.addSuppressed(IOException(corpus[2]))

        val report = formatCrashReport(root, metadata)

        corpus.forEach { assertFalse(report.contains(it)) }
        assertContains(report, "IllegalStateException")
        assertTrue(report.contains("CrashReportFormatterTest"))
        assertContains(report, "1.2.3 (45)")
    }

    @Test
    fun `never mentions health data fields`() {
        val throwable = RuntimeException("unexpected null value")

        val report = formatCrashReport(throwable, metadata)

        assertFalse(report.contains("heartRate", ignoreCase = true))
        assertFalse(report.contains("sleepSession", ignoreCase = true))
        assertFalse(report.contains("hrv", ignoreCase = true))
    }
}
