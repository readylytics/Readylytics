package app.readylytics.health.crashreport

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
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
