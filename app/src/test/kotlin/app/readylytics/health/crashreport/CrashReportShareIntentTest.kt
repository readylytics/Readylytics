package app.readylytics.health.crashreport

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.R
import app.readylytics.health.domain.githubissue.GitHubIssueType
import app.readylytics.health.domain.githubissue.IssueReportRequest
import app.readylytics.health.domain.githubissue.ReportChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun templateDeviceInfoSectionContainsExpectedBoldedLabels() {
        val section = buildTemplateDeviceInfoSection(context)

        assertTrue(section.contains("- **App Version:**"))
        assertTrue(section.contains("- **Device Manufacturer:**"))
        assertTrue(section.contains("- **Device Model:**"))
        assertTrue(section.contains("- **Android Version:**"))
        assertTrue(section.contains("- **Android SDK Level:**"))
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
    fun bugReportEmailBodyWithNoLogcatHasNoLogcatSection() {
        val body = buildBugReportEmailBody(context, crashReportText = null, logcatText = null)

        assertFalse(body.contains("## App Logs"))
        assertFalse(body.contains("{{"))
    }

    @Test
    fun bugReportEmailBodyWithShortLogcatTextEmbedsIt() {
        val logcatText = "l".repeat(50)

        val body =
            buildBugReportEmailBody(
                context,
                crashReportText = null,
                logcatText = logcatText,
                logcatAttached = false,
                logcatDurationMinutes = 30,
            )

        assertTrue(body.contains("## App Logs (last 30 min)"))
        assertTrue(body.contains(logcatText))
        assertFalse(body.contains("…truncated"))
        assertFalse(body.contains("{{"))
    }

    @Test
    fun bugReportEmailBodyWithLongLogcatTextIsTruncated() {
        val logcatText = "l".repeat(MAX_GITHUB_ISSUE_REPORT_LENGTH + 1000)

        val body =
            buildBugReportEmailBody(
                context,
                crashReportText = null,
                logcatText = logcatText,
                logcatAttached = false,
                logcatDurationMinutes = 60,
            )

        assertTrue(body.contains("…truncated"))
        assertFalse(body.contains("l".repeat(MAX_GITHUB_ISSUE_REPORT_LENGTH + 1)))
        assertFalse(body.contains("{{"))
    }

    @Test
    fun bugReportEmailBodyWithAttachedLogcatShowsAttachmentNoteInsteadOfRawText() {
        val logcatText = "l".repeat(50)

        val body =
            buildBugReportEmailBody(
                context,
                crashReportText = null,
                logcatText = logcatText,
                logcatAttached = true,
                logcatDurationMinutes = 15,
            )

        assertTrue(body.contains("## App Logs (last 15 min)"))
        assertTrue(body.contains("Attached as a text file."))
        assertFalse(body.contains(logcatText))
        assertFalse(body.contains("{{"))
    }

    @Test
    fun bugReportIntentWithLogcatTextMatchesEmailBody() {
        val logcatText = "m".repeat(50)

        val uri = buildBugReportIntent(context, crashReportText = null, logcatText = logcatText, logcatDurationMinutes = 45).data!!

        assertEquals(
            buildBugReportEmailBody(context, crashReportText = null, logcatText = logcatText, logcatDurationMinutes = 45),
            uri.getQueryParameter("body"),
        )
    }

    @Test
    fun featureRequestEmailBodyHasNoLeftoverTokens() {
        val body = buildFeatureRequestEmailBody(context)

        assertFalse(body.contains("{{"))
        assertTrue(body.contains("## Description"))
        assertTrue(body.contains("## Device & App Info"))
        assertTrue(body.contains(buildTemplateDeviceInfoSection(context)))
    }

    @Test
    fun bugReportIntentUsesLabelsInsteadOfTemplateAndMatchesEmailBody() {
        val crashText = "d".repeat(50)

        val uri = buildBugReportIntent(context, crashText).data!!

        assertEquals("bug", uri.getQueryParameter("labels"))
        assertNull(uri.getQueryParameter("template"))
        assertEquals(context.getString(R.string.github_issue_bug_title), uri.getQueryParameter("title"))
        assertEquals(buildBugReportEmailBody(context, crashText), uri.getQueryParameter("body"))
    }

    @Test
    fun bugReportIntentWithNoCrashTextStillMatchesEmailBody() {
        val uri = buildBugReportIntent(context).data!!

        assertEquals(buildBugReportEmailBody(context, null), uri.getQueryParameter("body"))
    }

    @Test
    fun featureRequestIntentUsesLabelsInsteadOfTemplateAndMatchesEmailBody() {
        val uri = buildFeatureRequestIntent(context).data!!

        assertEquals("enhancement", uri.getQueryParameter("labels"))
        assertNull(uri.getQueryParameter("template"))
        assertEquals(context.getString(R.string.github_issue_feature_title), uri.getQueryParameter("title"))
        assertEquals(buildFeatureRequestEmailBody(context), uri.getQueryParameter("body"))
    }

    @Test
    fun issueReportIntentForBugReportGithubMatchesDirectBuilder() {
        val crashText = "e".repeat(50)
        val request =
            IssueReportRequest(
                issueType = GitHubIssueType.BUG_REPORT,
                channel = ReportChannel.GITHUB,
                hasCrashReport = true,
                includeLogcat = false,
                logcatDurationMinutes = 30,
            )

        val uri = buildIssueReportIntent(context, request, crashText, null, null, null).data

        assertEquals(buildBugReportIntent(context, crashText, null, 30).data, uri)
    }

    @Test
    fun issueReportIntentForFeatureRequestGithubMatchesDirectBuilder() {
        val request =
            IssueReportRequest(
                issueType = GitHubIssueType.FEATURE_REQUEST,
                channel = ReportChannel.GITHUB,
                hasCrashReport = false,
                includeLogcat = false,
                logcatDurationMinutes = 15,
            )

        val uri = buildIssueReportIntent(context, request, null, null, null, null).data

        assertEquals(buildFeatureRequestIntent(context).data, uri)
    }
}
