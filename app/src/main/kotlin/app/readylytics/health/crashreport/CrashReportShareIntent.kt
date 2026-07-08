package app.readylytics.health.crashreport

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import app.readylytics.health.R
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
): Intent {
    val uri =
        GITHUB_ISSUES_NEW_URL
            .toUri()
            .buildUpon()
            .appendQueryParameter("template", "bug_report.md")
            .appendQueryParameter("title", context.getString(R.string.github_issue_bug_title))
            .appendQueryParameter("body", buildBugReportBody(context, crashReportText))
            .build()
    return Intent(Intent.ACTION_VIEW, uri)
}

fun buildFeatureRequestIntent(context: Context): Intent {
    val uri =
        GITHUB_ISSUES_NEW_URL
            .toUri()
            .buildUpon()
            .appendQueryParameter("template", "feature_request.md")
            .appendQueryParameter("title", context.getString(R.string.github_issue_feature_title))
            .appendQueryParameter("body", buildFeatureRequestBody(context))
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
): Intent =
    buildReportEmailIntent(
        context,
        subject = context.getString(R.string.github_issue_bug_title),
        body = buildBugReportEmailBody(context, crashReportText),
        attachmentFile = crashReportFile,
    )

fun buildFeatureRequestEmailIntent(context: Context): Intent =
    buildReportEmailIntent(
        context,
        subject = context.getString(R.string.github_issue_feature_title),
        body = buildFeatureRequestEmailBody(context),
    )

internal fun buildDeviceInfoSection(context: Context): String {
    val packageInfo =
        try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            return buildString {
                appendLine()
                appendLine("**Device Info:**")
                appendLine("- App Version: (unknown)")
                appendLine("- Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("- Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            }
        }

    val appVersionName = packageInfo.versionName ?: "(unknown)"
    val appVersionCode = PackageInfoCompat.getLongVersionCode(packageInfo)

    return buildString {
        appendLine()
        appendLine("**Device Info:**")
        appendLine("- App Version: $appVersionName ($appVersionCode)")
        appendLine("- Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("- Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
    }
}

internal fun buildBugReportBody(
    context: Context,
    crashReportText: String?,
): String {
    val deviceInfo = buildDeviceInfoSection(context)
    return if (crashReportText != null) {
        val truncated = crashReportText.length > MAX_GITHUB_ISSUE_REPORT_LENGTH
        val crashData = if (truncated) crashReportText.take(MAX_GITHUB_ISSUE_REPORT_LENGTH) else crashReportText
        buildString {
            appendLine("**Crash Details:**")
            appendLine("```")
            append(crashData)
            if (truncated) appendLine("\n…truncated") else appendLine()
            appendLine("```")
            append(deviceInfo)
        }
    } else {
        deviceInfo
    }
}

internal fun buildFeatureRequestBody(context: Context): String = buildDeviceInfoSection(context)

// Matches .github/ISSUE_TEMPLATE/*.md's Device & App Info bullet layout (one line per field),
// distinct from buildDeviceInfoSection's condensed 3-line format used by the GitHub body builders.
internal fun buildTemplateDeviceInfoSection(context: Context): String {
    val packageInfo =
        try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            return buildString {
                appendLine("- App Version: (unknown)")
                appendLine("- Device Manufacturer: ${Build.MANUFACTURER}")
                appendLine("- Device Model: ${Build.MODEL}")
                appendLine("- Android Version: ${Build.VERSION.RELEASE}")
                appendLine("- Android SDK Level: ${Build.VERSION.SDK_INT}")
            }
        }

    val appVersionName = packageInfo.versionName ?: "(unknown)"
    val appVersionCode = PackageInfoCompat.getLongVersionCode(packageInfo)

    return buildString {
        appendLine("- App Version: $appVersionName ($appVersionCode)")
        appendLine("- Device Manufacturer: ${Build.MANUFACTURER}")
        appendLine("- Device Model: ${Build.MODEL}")
        appendLine("- Android Version: ${Build.VERSION.RELEASE}")
        appendLine("- Android SDK Level: ${Build.VERSION.SDK_INT}")
    }
}

internal fun buildBugReportEmailBody(
    context: Context,
    crashReportText: String?,
): String {
    val crashSection =
        if (crashReportText != null) {
            val truncated = crashReportText.length > MAX_GITHUB_ISSUE_REPORT_LENGTH
            val crashData = if (truncated) crashReportText.take(MAX_GITHUB_ISSUE_REPORT_LENGTH) else crashReportText
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
    return context
        .getString(R.string.report_email_bug_template)
        .replace(CRASH_DETAILS_TOKEN, crashSection)
        .replace(DEVICE_INFO_TOKEN, buildTemplateDeviceInfoSection(context))
}

internal fun buildFeatureRequestEmailBody(context: Context): String =
    context
        .getString(R.string.report_email_feature_template)
        .replace(DEVICE_INFO_TOKEN, buildTemplateDeviceInfoSection(context))

internal fun buildGithubIssueBody(reportText: String): String {
    val truncated = reportText.length > MAX_GITHUB_ISSUE_REPORT_LENGTH
    val body = if (truncated) reportText.take(MAX_GITHUB_ISSUE_REPORT_LENGTH) else reportText
    return buildString {
        append("```\n")
        append(body)
        if (truncated) append("\n…truncated, see the email option for the full report")
        append("\n```")
    }
}
