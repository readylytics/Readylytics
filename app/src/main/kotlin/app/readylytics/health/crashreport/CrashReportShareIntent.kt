package app.readylytics.health.crashreport

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import app.readylytics.health.R
import app.readylytics.health.domain.githubissue.GitHubIssueType
import app.readylytics.health.domain.githubissue.IssueReportRequest
import app.readylytics.health.domain.githubissue.ReportChannel
import java.io.File

fun buildCrashReportShareIntent(
    context: Context,
    reportFile: File,
): Intent {
    val uri =
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            reportFile,
        )
    val sendIntent =
        Intent(Intent.ACTION_SEND).apply {
            type = "message/rfc822"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(context.getString(R.string.crash_report_email_address)))
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.crash_report_title))
            putExtra(Intent.EXTRA_TEXT, context.getString(R.string.crash_report_email_body))
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    return Intent.createChooser(sendIntent, context.getString(R.string.crash_report_chooser_title))
}

private const val GITHUB_ISSUES_NEW_URL = "https://github.com/readylytics/Readylytics/issues/new"

// GitHub/browsers reject request URLs above roughly 8000 characters. Below this, the GitHub-URL
// paths embed the full, untruncated report; above it they fall back to saving a file (see
// GithubIssueIntentResult.Oversized) instead of guessing a raw-text cutoff.
internal const val GITHUB_ISSUE_URL_MAX_LENGTH = 8000

// Only used by the Email paths, which have no URL-length constraint of their own but still cap
// inline report text for readability; the crash/logcat file is attached separately there.
internal const val MAX_GITHUB_ISSUE_REPORT_LENGTH = 2000

private const val CRASH_DETAILS_TOKEN = "{{CRASH_DETAILS}}"
private const val LOGCAT_DETAILS_TOKEN = "{{LOGCAT_DETAILS}}"
private const val DEVICE_INFO_TOKEN = "{{DEVICE_INFO}}"

// Result of trying to build a GitHub "new issue" deep link: either the Uri fits under
// GITHUB_ISSUE_URL_MAX_LENGTH and is ready to launch, or it doesn't and the full report needs to
// be saved to a file instead (see CrashReportFileExport + buildOversizedFallbackIntent).
sealed interface GithubIssueIntentResult {
    data class Ready(val intent: Intent) : GithubIssueIntentResult

    data class Oversized(
        val fullReport: String,
        val suggestedFilename: String,
        val title: String,
        val labels: String?,
    ) : GithubIssueIntentResult
}

private fun buildGithubUri(
    title: String,
    labels: String?,
    body: String,
): Uri =
    GITHUB_ISSUES_NEW_URL
        .toUri()
        .buildUpon()
        .apply { if (labels != null) appendQueryParameter("labels", labels) }
        .appendQueryParameter("title", title)
        .appendQueryParameter("body", body)
        .build()

// Builds the real Uri for a candidate (untruncated) body and checks its actual encoded length
// against GITHUB_ISSUE_URL_MAX_LENGTH, rather than guessing from the raw text length.
private fun githubIssueResult(
    title: String,
    labels: String?,
    fullBody: String,
    filenamePrefix: String,
): GithubIssueIntentResult {
    val uri = buildGithubUri(title, labels, fullBody)
    return if (uri.toString().length <= GITHUB_ISSUE_URL_MAX_LENGTH) {
        GithubIssueIntentResult.Ready(Intent(Intent.ACTION_VIEW, uri))
    } else {
        GithubIssueIntentResult.Oversized(
            fullReport = fullBody,
            suggestedFilename = CrashReportFileExport.suggestedFilename(filenamePrefix),
            title = title,
            labels = labels,
        )
    }
}

fun buildGithubIssueIntent(
    context: Context,
    reportText: String,
): GithubIssueIntentResult =
    githubIssueResult(
        title = context.getString(R.string.crash_report_title),
        labels = null,
        fullBody = buildGithubIssueBodyFull(reportText),
        filenamePrefix = "readylytics_crash_report",
    )

fun buildBugReportIntent(
    context: Context,
    crashReportText: String? = null,
    logcatText: String? = null,
    logcatDurationMinutes: Int? = null,
): GithubIssueIntentResult =
    githubIssueResult(
        title = context.getString(R.string.github_issue_bug_title),
        labels = "bug",
        fullBody = buildBugReportGithubBody(context, crashReportText, logcatText, logcatDurationMinutes),
        filenamePrefix = "readylytics_crash_report",
    )

fun buildFeatureRequestIntent(context: Context): Intent {
    val uri =
        buildGithubUri(
            title = context.getString(R.string.github_issue_feature_title),
            labels = "enhancement",
            body = buildFeatureRequestEmailBody(context),
        )
    return Intent(Intent.ACTION_VIEW, uri)
}

// Body for the GitHub issue opened once an oversized report has been saved to a file: device info
// plus a note pointing the user at the saved file, instead of embedding truncated text.
fun buildOversizedFallbackIntent(
    context: Context,
    oversized: GithubIssueIntentResult.Oversized,
    savedFilename: String,
): Intent {
    val body =
        buildString {
            appendLine(context.getString(R.string.github_issue_report_saved_to_file, savedFilename))
            appendLine()
            append(buildTemplateDeviceInfoSection(context))
        }
    return Intent(Intent.ACTION_VIEW, buildGithubUri(oversized.title, oversized.labels, body))
}

private fun buildReportEmailIntent(
    context: Context,
    subject: String,
    body: String,
    attachmentFile: File? = null,
): Intent {
    val sendIntent =
        Intent(Intent.ACTION_SEND).apply {
            type = "message/rfc822"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(context.getString(R.string.crash_report_email_address)))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            if (attachmentFile != null) {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", attachmentFile)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    return Intent.createChooser(sendIntent, context.getString(R.string.report_email_chooser_title))
}

fun buildBugReportEmailIntent(
    context: Context,
    crashReportFile: File?,
    crashReportText: String?,
    logcatFile: File? = null,
    logcatText: String? = null,
    logcatDurationMinutes: Int? = null,
): Intent =
    buildReportEmailIntent(
        context,
        subject = context.getString(R.string.github_issue_bug_title),
        body =
            buildBugReportEmailBody(
                context,
                crashReportText,
                logcatText,
                logcatAttached = logcatFile != null,
                logcatDurationMinutes = logcatDurationMinutes,
            ),
        attachmentFile = crashReportFile ?: logcatFile,
    )

fun buildFeatureRequestEmailIntent(context: Context): Intent =
    buildReportEmailIntent(
        context,
        subject = context.getString(R.string.github_issue_feature_title),
        body = buildFeatureRequestEmailBody(context),
    )

// Picks the right builder above for a submitted issue-report request, given the crash/logcat
// diagnostics already gathered by the caller (crash text/file when hasCrashReport, logcat
// text/file when includeLogcat).
fun buildIssueReportIntent(
    context: Context,
    request: IssueReportRequest,
    crashReportText: String?,
    crashReportFile: File?,
    logcatText: String?,
    logcatFile: File?,
): GithubIssueIntentResult =
    when (request.issueType) {
        GitHubIssueType.BUG_REPORT ->
            when (request.channel) {
                ReportChannel.GITHUB ->
                    buildBugReportIntent(context, crashReportText, logcatText, request.logcatDurationMinutes)
                ReportChannel.EMAIL ->
                    GithubIssueIntentResult.Ready(
                        buildBugReportEmailIntent(
                            context,
                            crashReportFile,
                            crashReportText,
                            logcatFile,
                            logcatText,
                            request.logcatDurationMinutes,
                        ),
                    )
            }
        GitHubIssueType.FEATURE_REQUEST ->
            when (request.channel) {
                ReportChannel.GITHUB -> GithubIssueIntentResult.Ready(buildFeatureRequestIntent(context))
                ReportChannel.EMAIL -> GithubIssueIntentResult.Ready(buildFeatureRequestEmailIntent(context))
            }
    }

// Looks up (versionName, versionCode), tolerating the rare case where the package can't be
// found. Wraps the SDK-33+ PackageInfoFlags overload since the plain int-flags one is deprecated.
private fun appVersionInfo(context: Context): Pair<String, Long> {
    val packageInfo =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            return "(unknown)" to 0L
        }
    return (packageInfo.versionName ?: "(unknown)") to PackageInfoCompat.getLongVersionCode(packageInfo)
}

// Matches .github/ISSUE_TEMPLATE/*.md's Device & App Info bullet layout (one bolded line per field).
internal fun buildTemplateDeviceInfoSection(context: Context): String {
    val (appVersionName, appVersionCode) = appVersionInfo(context)
    return buildString {
        appendLine("- **App Version:** $appVersionName ($appVersionCode)")
        appendLine("- **Device Manufacturer:** ${Build.MANUFACTURER}")
        appendLine("- **Device Model:** ${Build.MODEL}")
        appendLine("- **Android Version:** ${Build.VERSION.RELEASE}")
        appendLine("- **Android SDK Level:** ${Build.VERSION.SDK_INT}")
    }
}

// Truncates text at maxLength, returning whether truncation happened.
private fun truncateForReport(
    text: String,
    maxLength: Int = MAX_GITHUB_ISSUE_REPORT_LENGTH,
): Pair<String, Boolean> {
    val truncated = text.length > maxLength
    return (if (truncated) text.take(maxLength) else text) to truncated
}

// Shared composer for the bug-report body. truncateSections controls whether crash/logcat text
// is capped at MAX_GITHUB_ISSUE_REPORT_LENGTH (Email: yes, still bounded for readability since the
// full text is attached as a file) or left fully untruncated (GitHub URL: no, since the real Uri
// length is checked directly and oversized reports fall back to a saved file instead).
private fun composeBugReportBody(
    context: Context,
    crashReportText: String?,
    logcatText: String?,
    logcatAttached: Boolean,
    logcatDurationMinutes: Int?,
    truncateSections: Boolean,
): String {
    val crashSection =
        if (crashReportText != null) {
            val (crashData, truncated) =
                if (truncateSections) truncateForReport(crashReportText) else crashReportText to false
            buildString {
                appendLine("## Crash Details")
                appendLine("```")
                append(crashData)
                if (truncated) appendLine("\n…truncated") else appendLine()
                appendLine("```")
                appendLine()
            }
        } else {
            ""
        }
    val logcatSection = buildLogcatSection(logcatText, logcatAttached, logcatDurationMinutes, truncateSections)
    return context
        .getString(R.string.report_email_bug_template)
        .replace(CRASH_DETAILS_TOKEN, crashSection)
        .replace(LOGCAT_DETAILS_TOKEN, logcatSection)
        .replace(DEVICE_INFO_TOKEN, buildTemplateDeviceInfoSection(context))
}

internal fun buildBugReportEmailBody(
    context: Context,
    crashReportText: String?,
    logcatText: String? = null,
    logcatAttached: Boolean = false,
    logcatDurationMinutes: Int? = null,
): String =
    composeBugReportBody(
        context,
        crashReportText,
        logcatText,
        logcatAttached,
        logcatDurationMinutes,
        truncateSections = true,
    )

internal fun buildBugReportGithubBody(
    context: Context,
    crashReportText: String?,
    logcatText: String?,
    logcatDurationMinutes: Int?,
): String =
    composeBugReportBody(
        context,
        crashReportText,
        logcatText,
        logcatAttached = false,
        logcatDurationMinutes,
        truncateSections = false,
    )

private fun buildLogcatSection(
    logcatText: String?,
    logcatAttached: Boolean,
    logcatDurationMinutes: Int?,
    truncateSection: Boolean,
): String {
    if (logcatText == null) return ""
    return buildString {
        appendLine("## App Logs (last $logcatDurationMinutes min)")
        if (logcatAttached) {
            appendLine("Attached as a text file.")
        } else {
            val (logcatData, truncated) =
                if (truncateSection) truncateForReport(logcatText) else logcatText to false
            appendLine("```")
            append(logcatData)
            if (truncated) appendLine("\n…truncated") else appendLine()
            appendLine("```")
        }
        appendLine()
    }
}

internal fun buildFeatureRequestEmailBody(context: Context): String =
    context
        .getString(R.string.report_email_feature_template)
        .replace(DEVICE_INFO_TOKEN, buildTemplateDeviceInfoSection(context))

internal fun buildGithubIssueBodyFull(reportText: String): String =
    buildString {
        append("```\n")
        append(reportText)
        append("\n```")
    }
