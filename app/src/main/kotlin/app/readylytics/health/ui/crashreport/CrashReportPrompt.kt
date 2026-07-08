package app.readylytics.health.ui.crashreport

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readylytics.health.R
import app.readylytics.health.crashreport.CrashReportFileExport
import app.readylytics.health.crashreport.GithubIssueIntentResult
import app.readylytics.health.crashreport.buildCrashReportShareIntent
import app.readylytics.health.crashreport.buildGithubIssueIntent
import app.readylytics.health.crashreport.buildOversizedFallbackIntent

@Composable
fun CrashReportPrompt(viewModel: CrashReportViewModel = hiltViewModel()) {
    val showPrompt by viewModel.showPrompt.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var pendingOversized by remember { mutableStateOf<GithubIssueIntentResult.Oversized?>(null) }
    var showOversizedDialog by remember { mutableStateOf(false) }

    val saveLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            val oversized = pendingOversized
            pendingOversized = null
            if (uri == null || oversized == null) return@rememberLauncherForActivityResult
            val filename =
                CrashReportFileExport
                    .writeReport(context, uri, oversized.fullReport)
                    .getOrElse { oversized.suggestedFilename }
            context.startActivity(buildOversizedFallbackIntent(context, oversized, filename))
            viewModel.consumeReport()
        }

    if (showPrompt) {
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
                        when (val result = buildGithubIssueIntent(context, viewModel.reportText())) {
                            is GithubIssueIntentResult.Ready -> {
                                context.startActivity(result.intent)
                                viewModel.consumeReport()
                            }
                            is GithubIssueIntentResult.Oversized -> {
                                pendingOversized = result
                                showOversizedDialog = true
                            }
                        }
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

    OversizedReportDialog(
        isShown = showOversizedDialog,
        onDismiss = {
            showOversizedDialog = false
            pendingOversized = null
        },
        onSaveFile = { filename ->
            showOversizedDialog = false
            saveLauncher.launch(filename)
        },
        suggestedFilename = pendingOversized?.suggestedFilename ?: "",
    )
}
