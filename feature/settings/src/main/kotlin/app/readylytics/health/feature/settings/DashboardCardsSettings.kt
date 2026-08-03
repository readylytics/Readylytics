package app.readylytics.health.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.components.DropdownPreferenceItem
import app.readylytics.health.core.ui.components.SectionHeader
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode

@Composable
fun DashboardCardsSettingsSection(
    uiState: DashboardCardsSettingsState,
    onEvent: (SettingsEvent) -> Unit,
) {
    var selectedMode by remember { mutableStateOf<DashboardCardDisplayMode?>(null) }
    val modeLabels = dashboardCardDisplayModeLabels()

    Column {
        SectionHeader(stringResource(R.string.dashboard_cards_section_header))
        Column(modifier = Modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DropdownPreferenceItem(
                    label = stringResource(R.string.dashboard_cards_global_mode_label),
                    selectedDisplayValue =
                        selectedMode?.let { modeLabels.getValue(it) }
                            ?: stringResource(R.string.dashboard_cards_global_mode_placeholder),
                    options =
                        listOf(
                            DashboardCardDisplayMode.VALUE,
                            DashboardCardDisplayMode.GAUGE,
                            DashboardCardDisplayMode.BAR,
                        ),
                    onOptionSelected = { selectedMode = it },
                    optionLabel = { modeLabels.getValue(it) },
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(MaterialTheme.spacing.small))
                Button(
                    onClick = {
                        selectedMode?.let {
                            onEvent(SettingsEvent.DashboardGlobalDisplayModeApplyRequested(it))
                        }
                    },
                    enabled = selectedMode != null,
                ) {
                    Text(stringResource(R.string.dashboard_cards_global_mode_apply))
                }
            }
            Text(
                text = stringResource(R.string.dashboard_cards_global_mode_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = MaterialTheme.spacing.extraSmall),
            )
        }
    }

    if (uiState.showGlobalDisplayModeDialog) {
        GlobalDisplayModeConfirmDialog(
            onConfirm = { dontShowAgain ->
                onEvent(SettingsEvent.DashboardGlobalDisplayModeConfirmed(dontShowAgain))
            },
            onDismiss = { onEvent(SettingsEvent.DashboardGlobalDisplayModeDialogDismissed) },
        )
    }
}

@Composable
private fun dashboardCardDisplayModeLabels(): Map<DashboardCardDisplayMode, String> =
    mapOf(
        DashboardCardDisplayMode.GAUGE to stringResource(R.string.dashboard_cards_mode_gauge),
        DashboardCardDisplayMode.BAR to stringResource(R.string.dashboard_cards_mode_bar),
        DashboardCardDisplayMode.VALUE to stringResource(R.string.dashboard_cards_mode_value),
    )

@Composable
private fun GlobalDisplayModeConfirmDialog(
    onConfirm: (dontShowAgain: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var dontShowAgain by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dashboard_cards_override_dialog_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.dashboard_cards_override_dialog_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .toggleable(
                                value = dontShowAgain,
                                role = Role.Checkbox,
                                onValueChange = { dontShowAgain = it },
                            ).padding(top = MaterialTheme.spacing.small),
                ) {
                    Checkbox(checked = dontShowAgain, onCheckedChange = null)
                    Spacer(modifier = Modifier.width(MaterialTheme.spacing.extraSmall))
                    Text(
                        text = stringResource(R.string.dashboard_cards_override_dialog_dont_show_again),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(dontShowAgain) }) {
                Text(stringResource(R.string.dashboard_cards_override_dialog_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(app.readylytics.health.core.ui.R.string.action_cancel))
            }
        },
    )
}
