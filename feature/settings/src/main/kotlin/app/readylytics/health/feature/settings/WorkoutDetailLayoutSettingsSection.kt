package app.readylytics.health.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.components.SectionHeader
import app.readylytics.health.core.ui.R as CoreUiR

@Composable
fun WorkoutDetailLayoutSettingsSection(onEvent: (SettingsEvent) -> Unit) {
    var showResetDialog by rememberSaveable { mutableStateOf(false) }

    Column {
        SectionHeader(stringResource(R.string.workout_detail_layouts_section_header))
        Column(modifier = Modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal)) {
            OutlinedButton(onClick = { showResetDialog = true }) {
                Text(stringResource(R.string.workout_detail_layouts_reset_button))
            }
            Text(
                text = stringResource(R.string.workout_detail_layouts_reset_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = MaterialTheme.spacing.extraSmall),
            )
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(R.string.workout_detail_layouts_reset_dialog_title)) },
            text = { Text(stringResource(R.string.workout_detail_layouts_reset_dialog_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        onEvent(SettingsEvent.WorkoutDetailLayoutsResetConfirmed)
                    },
                ) {
                    Text(stringResource(R.string.workout_detail_layouts_reset_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(stringResource(CoreUiR.string.action_cancel))
                }
            },
        )
    }
}
