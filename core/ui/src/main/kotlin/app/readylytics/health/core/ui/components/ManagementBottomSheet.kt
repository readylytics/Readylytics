package app.readylytics.health.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SheetState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.model.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.core.ui.R

data class ManagementItem(
    val key: String,
    val label: String,
    val isVisible: Boolean,
    val supportedModes: List<DashboardCardDisplayMode>,
    val requestedMode: DashboardCardDisplayMode,
    val onVisibilityChanged: (Boolean) -> Unit,
    val onDisplayModeChanged: (DashboardCardDisplayMode) -> Unit,
)

data class ManagementSection(
    val title: String,
    val items: List<ManagementItem>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManagementBottomSheet(
    title: String,
    sections: List<ManagementSection>,
    onResetToDefaults: () -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        var selectedTabIndex by remember { mutableIntStateOf(0) }

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = MaterialTheme.spacing.pageSectionGap),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            start = MaterialTheme.spacing.pageHorizontal,
                            end = MaterialTheme.spacing.pageHorizontal,
                            bottom = MaterialTheme.spacing.small,
                        ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                )
                IconButton(onClick = onResetToDefaults) {
                    Icon(
                        imageVector = Icons.Outlined.RestartAlt,
                        contentDescription = stringResource(R.string.action_reset_to_defaults),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (sections.size > 1) {
                PrimaryTabRow(
                    selectedTabIndex = selectedTabIndex,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    sections.forEachIndexed { index, section ->
                        Tab(
                            selected = selectedTabIndex == index,
                            onClick = { selectedTabIndex = index },
                            text = { Text(section.title) },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(MaterialTheme.spacing.small))
            }

            val activeSection = sections.getOrElse(selectedTabIndex) { sections.first() }

            LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
            ) {
                items(activeSection.items, key = { it.key }) { item ->
                    ManagementRow(item)
                }
            }

            Button(
                onClick = onDismiss,
                modifier =
                    Modifier
                        .align(Alignment.End)
                        .padding(
                            end = MaterialTheme.spacing.pageHorizontal,
                            top = MaterialTheme.spacing.pageSectionGap,
                        ),
            ) {
                Text(stringResource(R.string.action_done))
            }
        }
    }
}

@Composable
private fun ManagementRow(item: ManagementItem) {
    if (item.supportedModes.isNotEmpty()) {
        ListItem(
            headlineContent = {
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            supportingContent = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DisplayModeDropdownSelector(
                        selectedMode = item.requestedMode,
                        supportedModes = item.supportedModes,
                        onModeSelected = item.onDisplayModeChanged,
                        modifier = Modifier.weight(1f),
                    )
                    Checkbox(
                        checked = item.isVisible,
                        onCheckedChange = item.onVisibilityChanged,
                    )
                }
            },
            modifier = Modifier.padding(vertical = MaterialTheme.spacing.extraSmall),
        )
    } else {
        ListItem(
            headlineContent = {
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            trailingContent = {
                Checkbox(
                    checked = item.isVisible,
                    onCheckedChange = item.onVisibilityChanged,
                )
            },
            modifier = Modifier.padding(vertical = MaterialTheme.spacing.extraSmall),
        )
    }
}
