package app.readylytics.health.ui.crashreport

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.readylytics.health.R

@Composable
fun OversizedReportDialog(
    isShown: Boolean,
    onDismiss: () -> Unit,
    onSaveFile: (filename: String) -> Unit,
    suggestedFilename: String,
) {
    if (!isShown) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.crash_report_too_large_title)) },
        text = { Text(stringResource(R.string.crash_report_too_large_body)) },
        confirmButton = {
            TextButton(onClick = {
                onSaveFile(suggestedFilename)
            }) {
                Text(stringResource(R.string.crash_report_too_large_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.crash_report_too_large_cancel))
            }
        },
    )
}
