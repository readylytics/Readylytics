package app.readylytics.health.ui.crashreport

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readylytics.health.R
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.model.domain.githubissue.ReportChannel
import app.readylytics.health.crashreport.CrashReportFileExport
import app.readylytics.health.crashreport.GithubIssueIntentResult
import app.readylytics.health.crashreport.buildCrashReportShareIntent
import app.readylytics.health.crashreport.buildGithubIssueIntent
import app.readylytics.health.crashreport.buildOversizedFallbackIntent

@Composable
fun CrashReportPrompt(viewModel: CrashReportViewModel = hiltViewModel()) {
    val showPrompt by viewModel.showPrompt.collectAsStateWithLifecycle()
    val promptKind by viewModel.promptKind.collectAsStateWithLifecycle()
    val copy = startupReportCopy(promptKind)
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
            viewModel.consumePromptReport()
        }

    if (showPrompt) {
        var selectedChannel by remember { mutableStateOf(ReportChannel.EMAIL) }

        AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text(copy.dialogTitle) },
            text = {
                Column {
                    Text(copy.dialogBody)
                    Spacer(Modifier.height(MaterialTheme.spacing.small))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        ReportChannel.entries.forEachIndexed { index, channel ->
                            SegmentedButton(
                                selected = selectedChannel == channel,
                                onClick = { selectedChannel = channel },
                                shape = SegmentedButtonDefaults.itemShape(index, ReportChannel.entries.size),
                                label = {
                                    Text(
                                        when (channel) {
                                            ReportChannel.EMAIL ->
                                                stringResource(
                                                    R.string.crash_report_dialog_send_email,
                                                )
                                            ReportChannel.GITHUB ->
                                                stringResource(
                                                    R.string.crash_report_dialog_send_github,
                                                )
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    when (selectedChannel) {
                        ReportChannel.EMAIL -> {
                            context.startActivity(
                                buildCrashReportShareIntent(
                                    context,
                                    viewModel.promptReportFile(),
                                    subject = copy.reportTitle,
                                    body = copy.emailBody,
                                ),
                            )
                            viewModel.consumePromptReport()
                        }
                        ReportChannel.GITHUB -> {
                            val result =
                                buildGithubIssueIntent(
                                    context,
                                    viewModel.promptReportText(),
                                    title = copy.reportTitle,
                                    filenamePrefix = copy.filenamePrefix,
                                )
                            when (result) {
                                is GithubIssueIntentResult.Ready -> {
                                    context.startActivity(result.intent)
                                    viewModel.consumePromptReport()
                                }
                                is GithubIssueIntentResult.Oversized -> {
                                    pendingOversized = result
                                    showOversizedDialog = true
                                }
                            }
                        }
                    }
                }) {
                    Text(stringResource(R.string.crash_report_dialog_send))
                }
            },
            dismissButton = {
                FlowRow {
                    TextButton(
                        onClick = viewModel::consumePromptReport,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Text(stringResource(R.string.crash_report_dialog_clear))
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

/** Dialog and share copy for the report the startup prompt is offering. */
private data class StartupReportCopy(
    val dialogTitle: String,
    val dialogBody: String,
    val reportTitle: String,
    val emailBody: String,
    val filenamePrefix: String,
)

@Composable
private fun startupReportCopy(kind: StartupReportKind?): StartupReportCopy =
    if (kind == StartupReportKind.RECALC_DIAGNOSTIC) {
        StartupReportCopy(
            dialogTitle = stringResource(R.string.recalc_diagnostic_dialog_title),
            dialogBody = stringResource(R.string.recalc_diagnostic_dialog_body),
            reportTitle = stringResource(R.string.recalc_diagnostic_report_title),
            emailBody = stringResource(R.string.recalc_diagnostic_email_body),
            filenamePrefix = "readylytics_recalc_report",
        )
    } else {
        StartupReportCopy(
            dialogTitle = stringResource(R.string.crash_report_dialog_title),
            dialogBody = stringResource(R.string.crash_report_dialog_body),
            reportTitle = stringResource(R.string.crash_report_title),
            emailBody = stringResource(R.string.crash_report_email_body),
            filenamePrefix = "readylytics_crash_report",
        )
    }
