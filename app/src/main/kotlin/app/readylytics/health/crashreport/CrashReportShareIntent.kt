package app.readylytics.health.crashreport

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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

// GitHub/browsers reject request URLs above roughly 8000 characters, and percent-encoding a
// stack trace can expand it 2-3x, so the raw report text is capped well below that.
private const val MAX_GITHUB_ISSUE_REPORT_LENGTH = 2000

private const val CRASH_DETAILS_TOKEN = "{{CRASH_DETAILS}}"
private const val LOGCAT_DETAILS_TOKEN = "{{LOGCAT_DETAILS}}"
private const val DEVICE_INFO_TOKEN = "{{DEVICE_INFO}}"

fun buildGithubIssueIntent(
    context: Context,
    reportText: String,
): Intent {
    val uri =
        GITHUB_ISSUES_NEW_URL
            .toUri()
            .buildUpon()
            .appendQueryParameter("title", context.getString(R.string.crash_report_title))
            .appendQueryParameter("body", buildGithubIssueBody(reportText))
            .build()
    return Intent(Intent.ACTION_VIEW, uri)
}

fun buildBugReportIntent(
    context: Context,
    crashReportText: String? = null,
    logcatText: String? = null,
    logcatDurationMinutes: Int? = null,
): Intent {
    val uri =
        GITHUB_ISSUES_NEW_URL
            .toUri()
            .buildUpon()
            .appendQueryParameter("labels", "bug")
            .appendQueryParameter("title", context.getString(R.string.github_issue_bug_title))
            .appendQueryParameter(
                "body",
                buildBugReportEmailBody(context, crashReportText, logcatText, logcatDurationMinutes = logcatDurationMinutes),
            )
            .build()
    return Intent(Intent.ACTION_VIEW, uri)
}

fun buildFeatureRequestIntent(context: Context): Intent {
    val uri =
        GITHUB_ISSUES_NEW_URL
            .toUri()
            .buildUpon()
            .appendQueryParameter("labels", "enhancement")
            .appendQueryParameter("title", context.getString(R.string.github_issue_feature_title))
            .appendQueryParameter("body", buildFeatureRequestEmailBody(context))
            .build()
    return Intent(Intent.ACTION_VIEW, uri)
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
): Intent =
    when (request.issueType) {
        GitHubIssueType.BUG_REPORT ->
            when (request.channel) {
                ReportChannel.GITHUB ->
                    buildBugReportIntent(context, crashReportText, logcatText, request.logcatDurationMinutes)
                ReportChannel.EMAIL ->
                    buildBugReportEmailIntent(
                        context,
                        crashReportFile,
                        crashReportText,
                        logcatFile,
                        logcatText,
                        request.logcatDurationMinutes,
                    )
            }
        GitHubIssueType.FEATURE_REQUEST ->
            when (request.channel) {
                ReportChannel.GITHUB -> buildFeatureRequestIntent(context)
                ReportChannel.EMAIL -> buildFeatureRequestEmailIntent(context)
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

// Truncates text at MAX_GITHUB_ISSUE_REPORT_LENGTH, returning whether truncation happened.
private fun truncateForReport(text: String): Pair<String, Boolean> {
    val truncated = text.length > MAX_GITHUB_ISSUE_REPORT_LENGTH
    return (if (truncated) text.take(MAX_GITHUB_ISSUE_REPORT_LENGTH) else text) to truncated
}

internal fun buildBugReportEmailBody(
    context: Context,
    crashReportText: String?,
    logcatText: String? = null,
    logcatAttached: Boolean = false,
    logcatDurationMinutes: Int? = null,
): String {
    val crashSection =
        if (crashReportText != null) {
            val (crashData, truncated) = truncateForReport(crashReportText)
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
    val logcatSection = buildLogcatSection(logcatText, logcatAttached, logcatDurationMinutes)
    return context
        .getString(R.string.report_email_bug_template)
        .replace(CRASH_DETAILS_TOKEN, crashSection)
        .replace(LOGCAT_DETAILS_TOKEN, logcatSection)
        .replace(DEVICE_INFO_TOKEN, buildTemplateDeviceInfoSection(context))
}

private fun buildLogcatSection(
    logcatText: String?,
    logcatAttached: Boolean,
    logcatDurationMinutes: Int?,
): String {
    if (logcatText == null) return ""
    return buildString {
        appendLine("## App Logs (last $logcatDurationMinutes min)")
        if (logcatAttached) {
            appendLine("Attached as a text file.")
        } else {
            val (logcatData, truncated) = truncateForReport(logcatText)
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

internal fun buildGithubIssueBody(reportText: String): String {
    val (body, truncated) = truncateForReport(reportText)
    return buildString {
        append("```\n")
        append(body)
        if (truncated) append("\n…truncated, see the email option for the full report")
        append("\n```")
    }
}
