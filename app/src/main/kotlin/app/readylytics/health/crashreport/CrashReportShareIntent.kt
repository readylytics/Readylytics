package app.readylytics.health.crashreport

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
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
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.crash_report_email_subject))
            putExtra(Intent.EXTRA_TEXT, context.getString(R.string.crash_report_email_body))
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    return Intent.createChooser(sendIntent, context.getString(R.string.crash_report_chooser_title))
}
