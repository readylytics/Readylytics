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

@RunWith(AndroidJUnit4::class)
class CrashReportShareIntentTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun ready(result: GithubIssueIntentResult): GithubIssueIntentResult.Ready =
        result as GithubIssueIntentResult.Ready

    @Test
    fun shortReportIsFencedButNotTruncated() {
        val reportText = "a".repeat(50)

        val body = buildGithubIssueBodyFull(reportText)

        assertTrue(body.startsWith("```\n"))
        assertTrue(body.endsWith("\n```"))
        assertTrue(body.contains(reportText))
        assertFalse(body.contains("…truncated"))
    }

    @Test
    fun longReportIsNotTruncated() {
        val reportText = "a".repeat(MAX_GITHUB_ISSUE_REPORT_LENGTH + 1000)

        val body = buildGithubIssueBodyFull(reportText)

        assertTrue(body.contains(reportText))
        assertFalse(body.contains("…truncated"))
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
    fun bugReportGithubBodyIsNeverTruncatedEvenWhenLong() {
        val crashText = "c".repeat(MAX_GITHUB_ISSUE_REPORT_LENGTH + 1000)
        val logcatText = "l".repeat(MAX_GITHUB_ISSUE_REPORT_LENGTH + 1000)

        val body = buildBugReportGithubBody(context, crashText, logcatText, 30)

        assertTrue(body.contains(crashText))
        assertTrue(body.contains(logcatText))
        assertFalse(body.contains("…truncated"))
    }

    @Test
    fun bugReportIntentWithLogcatTextMatchesGithubBody() {
        val logcatText = "m".repeat(50)

        val uri =
            ready(
                buildBugReportIntent(
                    context,
                    crashReportText = null,
                    logcatText = logcatText,
                    logcatDurationMinutes = 45,
                ),
            ).intent.data!!

        assertEquals(
            buildBugReportGithubBody(context, null, logcatText, 45),
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
    fun bugReportIntentUsesLabelsInsteadOfTemplateAndMatchesGithubBody() {
        val crashText = "d".repeat(50)

        val uri = ready(buildBugReportIntent(context, crashText)).intent.data!!

        assertEquals("bug", uri.getQueryParameter("labels"))
        assertNull(uri.getQueryParameter("template"))
        assertEquals(context.getString(R.string.github_issue_bug_title), uri.getQueryParameter("title"))
        assertEquals(buildBugReportGithubBody(context, crashText, null, null), uri.getQueryParameter("body"))
    }

    @Test
    fun bugReportIntentWithNoCrashTextStillMatchesGithubBody() {
        val uri = ready(buildBugReportIntent(context)).intent.data!!

        assertEquals(buildBugReportGithubBody(context, null, null, null), uri.getQueryParameter("body"))
    }

    @Test
    fun featureRequestIntentUsesLabelsInsteadOfTemplateAndMatchesEmailBody() {
        val uri = ready(buildFeatureRequestIntent(context)).intent.data!!

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

        val result = buildIssueReportIntent(context, request, crashText, null, null, null)
        val expected = buildBugReportIntent(context, crashText, null, 30)

        assertEquals(ready(expected).intent.data, ready(result).intent.data)
        assertEquals(ready(expected).intent.action, ready(result).intent.action)
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

        val result = buildIssueReportIntent(context, request, null, null, null, null)
        val expected = buildFeatureRequestIntent(context)

        assertEquals(ready(expected).intent.data, ready(result).intent.data)
        assertEquals(ready(expected).intent.action, ready(result).intent.action)
    }

    @Test
    fun githubIssueIntentReadyWhenReportFitsUnderUrlLimit() {
        // ~3000 raw chars is over the old 2000-char guess but still comfortably under the real
        // 8000-char URL limit once percent-encoded -- it must no longer be truncated for no reason.
        val reportText = "z".repeat(3000)

        val result = buildGithubIssueIntent(context, reportText)

        assertTrue(result is GithubIssueIntentResult.Ready)
        val body = ready(result).intent.data!!.getQueryParameter("body")!!
        assertTrue(body.contains(reportText))
    }

    @Test
    fun githubIssueIntentOversizedWhenReportHuge() {
        val reportText = "z".repeat(20000)

        val result = buildGithubIssueIntent(context, reportText)

        assertTrue(result is GithubIssueIntentResult.Oversized)
        val oversized = result as GithubIssueIntentResult.Oversized
        assertTrue(oversized.fullReport.contains(reportText))
        assertNull(oversized.labels)
        assertEquals(context.getString(R.string.crash_report_title), oversized.title)
        assertTrue(oversized.suggestedFilename.matches(Regex("readylytics_crash_report_.*\\.txt")))
    }

    @Test
    fun bugReportIntentOversizedCarriesBugLabel() {
        val crashText = "z".repeat(20000)

        val result = buildBugReportIntent(context, crashText)

        assertTrue(result is GithubIssueIntentResult.Oversized)
        val oversized = result as GithubIssueIntentResult.Oversized
        assertEquals("bug", oversized.labels)
        assertEquals(context.getString(R.string.github_issue_bug_title), oversized.title)
    }

    @Test
    fun oversizedFallbackIntentContainsDeviceInfoAndFilenameNote() {
        val oversized =
            GithubIssueIntentResult.Oversized(
                fullReport = "z".repeat(20000),
                suggestedFilename = "readylytics_crash_report_2026-01-01_120000.txt",
                title = context.getString(R.string.github_issue_bug_title),
                labels = "bug",
            )

        val intent = buildOversizedFallbackIntent(context, oversized, oversized.suggestedFilename)
        val uri = intent.data!!

        assertTrue(uri.toString().length <= GITHUB_ISSUE_URL_MAX_LENGTH)
        assertEquals("bug", uri.getQueryParameter("labels"))
        val body = uri.getQueryParameter("body")!!
        assertTrue(body.contains(oversized.suggestedFilename))
        assertTrue(body.contains(buildTemplateDeviceInfoSection(context)))
        assertFalse(body.contains("z".repeat(20000)))
    }

    @Test
    fun suggestedFilenameMatchesTimestampPattern() {
        val filename = CrashReportFileExport.suggestedFilename("readylytics_crash_report")

        assertTrue(filename.matches(Regex("readylytics_crash_report_\\d{4}-\\d{2}-\\d{2}_\\d{6}-\\d{3}\\.txt")))
    }
}
