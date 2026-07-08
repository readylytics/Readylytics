package app.readylytics.health.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.components.SettingsToggleItem
import app.readylytics.health.domain.githubissue.GitHubIssueType
import app.readylytics.health.domain.githubissue.IssueReportRequest
import app.readylytics.health.domain.githubissue.ReportChannel
import app.readylytics.health.feature.settings.R
import kotlin.math.roundToInt

private const val LOGCAT_MIN_MINUTES = 15f
private const val LOGCAT_MAX_MINUTES = 120f
private const val LOGCAT_STEP_MINUTES = 15
private const val LOGCAT_SLIDER_STEPS =
    ((LOGCAT_MAX_MINUTES - LOGCAT_MIN_MINUTES) / LOGCAT_STEP_MINUTES).toInt() - 1

@Composable
fun IssueReportDialog(
    reportType: GitHubIssueType,
    hasCrashReport: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (IssueReportRequest) -> Unit,
) {
    val titleRes =
        when (reportType) {
            GitHubIssueType.BUG_REPORT -> R.string.settings_item_report_bug
            GitHubIssueType.FEATURE_REQUEST -> R.string.settings_item_request_feature
        }
    val hasCrash = reportType == GitHubIssueType.BUG_REPORT && hasCrashReport
    val canOfferLogcat = reportType == GitHubIssueType.BUG_REPORT && !hasCrash
    var selectedChannel by remember(reportType) { mutableStateOf(ReportChannel.EMAIL) }
    var includeLogcat by remember(reportType) { mutableStateOf(false) }
    var logcatDurationMinutes by remember(reportType) { mutableFloatStateOf(LOGCAT_MIN_MINUTES) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            Column {
                Text(stringResource(R.string.settings_issue_dialog_body))
                Spacer(modifier = Modifier.height(MaterialTheme.spacing.small))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ReportChannel.entries.forEachIndexed { index, channel ->
                        SegmentedButton(
                            selected = selectedChannel == channel,
                            onClick = { selectedChannel = channel },
                            shape =
                                SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = ReportChannel.entries.size,
                                ),
                            label = {
                                Text(
                                    text =
                                        when (channel) {
                                            ReportChannel.EMAIL ->
                                                stringResource(R.string.settings_issue_dialog_send_email)
                                            ReportChannel.GITHUB ->
                                                stringResource(R.string.settings_issue_dialog_send_github)
                                        },
                                )
                            },
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.settings_issue_dialog_channel_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = MaterialTheme.spacing.small),
                )
                if (canOfferLogcat) {
                    SettingsToggleItem(
                        label = stringResource(R.string.settings_issue_dialog_logcat_toggle),
                        description = stringResource(R.string.settings_issue_dialog_logcat_help),
                        checked = includeLogcat,
                        onCheckedChange = { includeLogcat = it },
                        modifier = Modifier.padding(top = MaterialTheme.spacing.small),
                    )
                    if (includeLogcat) {
                        ThresholdSliderItem(
                            label =
                                stringResource(
                                    R.string.settings_issue_dialog_logcat_duration,
                                    logcatDurationMinutes.roundToInt(),
                                ),
                            value = logcatDurationMinutes,
                            onValueChange = { logcatDurationMinutes = it },
                            valueRange = LOGCAT_MIN_MINUTES..LOGCAT_MAX_MINUTES,
                            description = stringResource(R.string.settings_issue_dialog_logcat_help),
                            steps = LOGCAT_SLIDER_STEPS,
                            displayValue =
                                stringResource(
                                    R.string.settings_issue_dialog_logcat_duration_value,
                                    logcatDurationMinutes.roundToInt(),
                                ),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSubmit(
                    IssueReportRequest(
                        issueType = reportType,
                        channel = selectedChannel,
                        hasCrashReport = hasCrash,
                        includeLogcat = canOfferLogcat && includeLogcat,
                        logcatDurationMinutes = logcatDurationMinutes.roundToInt(),
                    ),
                )
                onDismiss()
            }) {
                Text(stringResource(R.string.settings_issue_dialog_submit))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(app.readylytics.health.core.ui.R.string.action_cancel))
            }
        },
    )
}
