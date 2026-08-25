package app.readylytics.health.core.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.model.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.core.ui.R

/**
 * Read-only exposed dropdown for picking a card's visualization mode. Shows only the modes a
 * card actually supports, so single-mode cards (steps, heart rate, blood pressure) never present
 * an empty choice. Used by the dashboard/vitals/sleep card-management sheets, mirroring the
 * on-card three-dot menu's option set.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisplayModeDropdownSelector(
    selectedMode: DashboardCardDisplayMode,
    supportedModes: List<DashboardCardDisplayMode>,
    onModeSelected: (DashboardCardDisplayMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    val displayValue = modeLabel(selectedMode)

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier.padding(top = MaterialTheme.spacing.extraSmall),
    ) {
        OutlinedTextField(
            value = displayValue,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.display_mode_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.exposedDropdownSize(),
        ) {
            supportedModes.forEach { option ->
                DropdownMenuItem(
                    text = { Text(modeLabel(option)) },
                    onClick = {
                        onModeSelected(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun modeLabel(mode: DashboardCardDisplayMode): String =
    when (mode) {
        DashboardCardDisplayMode.GAUGE -> stringResource(R.string.mode_gauge)
        DashboardCardDisplayMode.BAR -> stringResource(R.string.mode_bar)
        DashboardCardDisplayMode.VALUE -> stringResource(R.string.mode_value)
    }
