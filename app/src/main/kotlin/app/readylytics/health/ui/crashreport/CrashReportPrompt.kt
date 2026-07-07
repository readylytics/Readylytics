package app.readylytics.health.ui.crashreport

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readylytics.health.R
import app.readylytics.health.crashreport.buildCrashReportShareIntent
import app.readylytics.health.crashreport.buildGithubIssueIntent

@Composable
fun CrashReportPrompt(viewModel: CrashReportViewModel = hiltViewModel()) {
    val showPrompt by viewModel.showPrompt.collectAsStateWithLifecycle()
    if (!showPrompt) return

    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = viewModel::dismiss,
        title = { Text(stringResource(R.string.crash_report_dialog_title)) },
        text = { Text(stringResource(R.string.crash_report_dialog_body)) },
        confirmButton = {
            TextButton(onClick = {
                context.startActivity(buildCrashReportShareIntent(context, viewModel.reportFile()))
                viewModel.consumeReport()
            }) {
                Text(stringResource(R.string.crash_report_dialog_send_email))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    context.startActivity(buildGithubIssueIntent(context, viewModel.reportText()))
                    viewModel.consumeReport()
                }) {
                    Text(stringResource(R.string.crash_report_dialog_send_github))
                }
                TextButton(onClick = viewModel::dismiss) {
                    Text(stringResource(R.string.crash_report_dialog_dismiss))
                }
            }
        },
    )
}
