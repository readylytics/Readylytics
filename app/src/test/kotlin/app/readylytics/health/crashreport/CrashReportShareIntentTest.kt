package app.readylytics.health.crashreport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Must match the private MAX_GITHUB_ISSUE_REPORT_LENGTH in CrashReportShareIntent.kt.
private const val MAX_GITHUB_ISSUE_REPORT_LENGTH = 2000

class CrashReportShareIntentTest {
    @Test
    fun shortReportIsFencedButNotTruncated() {
        val reportText = "a".repeat(50)

        val body = buildGithubIssueBody(reportText)

        assertTrue(body.startsWith("```\n"))
        assertTrue(body.endsWith("\n```"))
        assertTrue(body.contains(reportText))
        assertFalse(body.contains("…truncated"))
    }

    @Test
    fun longReportIsTruncatedWithSuffix() {
        val reportText = "a".repeat(MAX_GITHUB_ISSUE_REPORT_LENGTH + 1000)

        val body = buildGithubIssueBody(reportText)

        assertTrue(body.contains("…truncated, see the email option for the full report"))
        assertFalse(body.contains("a".repeat(MAX_GITHUB_ISSUE_REPORT_LENGTH + 1)))
        assertTrue(body.length < 8000)
    }
}
