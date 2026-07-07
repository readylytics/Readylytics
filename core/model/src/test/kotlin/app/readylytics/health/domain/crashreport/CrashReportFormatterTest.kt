package app.readylytics.health.domain.crashreport

import org.junit.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

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
    fun `includes stack trace and diagnostic metadata`() {
        val throwable = IllegalStateException("boom")

        val report = formatCrashReport(throwable, metadata)

        assertContains(report, "IllegalStateException")
        assertContains(report, "boom")
        assertContains(report, "1.2.3 (45)")
        assertContains(report, "14 (SDK 34)")
        assertContains(report, "Google Pixel 9")
        assertContains(report, "2026-07-07T12:00:00Z")
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
