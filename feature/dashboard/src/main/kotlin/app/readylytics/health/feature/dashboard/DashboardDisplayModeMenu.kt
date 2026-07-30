package app.readylytics.health.feature.dashboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.domain.dashboard.DashboardCardSpec
import app.readylytics.health.feature.dashboard.R

@Composable
fun DashboardDisplayModeMenu(
    specification: DashboardCardSpec,
    requestedMode: DashboardCardDisplayMode,
    isSelectionAvailable: Boolean,
    onModeSelected: (DashboardCardDisplayMode) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(id = R.string.menu_content_description_visualization_style)
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            specification.supportedModes.forEach { mode ->
                val textRes = when (mode) {
                    DashboardCardDisplayMode.GAUGE -> R.string.mode_gauge
                    DashboardCardDisplayMode.BAR -> R.string.mode_bar
                    DashboardCardDisplayMode.VALUE -> R.string.mode_value
                }

                val enabled = when (mode) {
                    DashboardCardDisplayMode.GAUGE, DashboardCardDisplayMode.BAR -> isSelectionAvailable
                    DashboardCardDisplayMode.VALUE -> true
                }

                val isSelected = mode == requestedMode
                val modeName = stringResource(id = textRes)
                // A dedicated contentDescription (rather than relying on the visible text plus
                // the `selected` boolean alone) gives TalkBack a single, unambiguous announcement
                // that names the category ("Visualization style") and the selection state.
                val itemDescription = if (isSelected) {
                    stringResource(R.string.menu_item_description_mode_selected, modeName)
                } else {
                    stringResource(R.string.menu_item_description_mode, modeName)
                }

                DropdownMenuItem(
                    text = { Text(modeName) },
                    onClick = {
                        onModeSelected(mode)
                        expanded = false
                    },
                    enabled = enabled,
                    modifier = Modifier.semantics {
                        selected = isSelected
                        contentDescription = itemDescription
                    }
                )
            }
        }
    }
}
