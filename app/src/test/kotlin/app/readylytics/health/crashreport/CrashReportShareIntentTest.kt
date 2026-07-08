package app.readylytics.health.crashreport

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// Must match the private MAX_GITHUB_ISSUE_REPORT_LENGTH in CrashReportShareIntent.kt.
private const val MAX_GITHUB_ISSUE_REPORT_LENGTH = 2000

@RunWith(AndroidJUnit4::class)
class CrashReportShareIntentTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

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

    @Test
    fun deviceInfoSectionContainsExpectedLabels() {
        val section = buildDeviceInfoSection(context)

        assertTrue(section.contains("**Device Info:**"))
        assertTrue(section.contains("- App Version:"))
        assertTrue(section.contains("- Device:"))
        assertTrue(section.contains("- Android:"))
    }

    @Test
    fun featureRequestBodyIsJustDeviceInfo() {
        val body = buildFeatureRequestBody(context)

        assertEquals(buildDeviceInfoSection(context), body)
    }

    @Test
    fun bugReportBodyWithNoCrashTextIsJustDeviceInfo() {
        val body = buildBugReportBody(context, crashReportText = null)

        assertEquals(buildDeviceInfoSection(context), body)
    }

    @Test
    fun bugReportBodyWithShortCrashTextIncludesCrashDetailsAndDeviceInfo() {
        val crashText = "b".repeat(50)

        val body = buildBugReportBody(context, crashText)

        assertTrue(body.contains("**Crash Details:**"))
        assertTrue(body.contains("```"))
        assertTrue(body.contains(crashText))
        assertFalse(body.contains("…truncated"))
        assertTrue(body.contains("**Device Info:**"))
    }

    @Test
    fun bugReportBodyWithLongCrashTextIsTruncated() {
        val crashText = "b".repeat(MAX_GITHUB_ISSUE_REPORT_LENGTH + 1000)

        val body = buildBugReportBody(context, crashText)

        assertTrue(body.contains("…truncated"))
        assertFalse(body.contains("b".repeat(MAX_GITHUB_ISSUE_REPORT_LENGTH + 1)))
        assertTrue(body.contains("**Device Info:**"))
        assertTrue(body.length < 8000)
    }

    @Test
    fun templateDeviceInfoSectionContainsExpectedLabels() {
        val section = buildTemplateDeviceInfoSection(context)

        assertTrue(section.contains("- App Version:"))
        assertTrue(section.contains("- Device Manufacturer:"))
        assertTrue(section.contains("- Device Model:"))
        assertTrue(section.contains("- Android Version:"))
        assertTrue(section.contains("- Android SDK Level:"))
    }

    @Test
    fun bugReportEmailBodyWithNoCrashTextHasNoCrashSection() {
        val body = buildBugReportEmailBody(context, crashReportText = null)

        assertFalse(body.contains("## Crash Details"))
        assertFalse(body.contains("{{"))
        assertTrue(body.contains("## Summary"))
        assertTrue(body.contains("## Device & App Info"))
        assertTrue(body.contains(buildTemplateDeviceInfoSection(context)))
    }

    @Test
    fun bugReportEmailBodyWithShortCrashTextIncludesCrashSection() {
        val crashText = "c".repeat(50)

        val body = buildBugReportEmailBody(context, crashText)

        assertTrue(body.contains("## Crash Details"))
        assertTrue(body.contains(crashText))
        assertFalse(body.contains("…truncated"))
        assertFalse(body.contains("{{"))
        assertTrue(body.contains(buildTemplateDeviceInfoSection(context)))
    }

    @Test
    fun bugReportEmailBodyWithLongCrashTextIsTruncated() {
        val crashText = "c".repeat(MAX_GITHUB_ISSUE_REPORT_LENGTH + 1000)

        val body = buildBugReportEmailBody(context, crashText)

        assertTrue(body.contains("…truncated"))
        assertFalse(body.contains("c".repeat(MAX_GITHUB_ISSUE_REPORT_LENGTH + 1)))
        assertFalse(body.contains("{{"))
    }

    @Test
    fun featureRequestEmailBodyHasNoLeftoverTokens() {
        val body = buildFeatureRequestEmailBody(context)

        assertFalse(body.contains("{{"))
        assertTrue(body.contains("## Description"))
        assertTrue(body.contains("## Device & App Info"))
        assertTrue(body.contains(buildTemplateDeviceInfoSection(context)))
    }
}
