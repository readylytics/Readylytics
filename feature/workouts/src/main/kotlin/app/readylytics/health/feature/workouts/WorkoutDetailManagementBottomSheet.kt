package app.readylytics.health.feature.workouts

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.model.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.core.model.domain.workouts.detail.WorkoutDetailItemConfiguration
import app.readylytics.health.core.model.domain.workouts.detail.WorkoutDetailItemId
import app.readylytics.health.core.model.domain.workouts.detail.WorkoutLayoutType
import app.readylytics.health.core.ui.components.ManagementBottomSheet
import app.readylytics.health.core.ui.components.ManagementItem
import app.readylytics.health.core.ui.components.ManagementSection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutDetailManagementBottomSheet(
    layoutType: WorkoutLayoutType,
    itemConfigurations: List<WorkoutDetailItemConfiguration>,
    onItemVisibilityChanged: (WorkoutDetailItemId, Boolean) -> Unit,
    onResetToDefaults: () -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState,
    modifier: Modifier = Modifier,
) {
    ManagementBottomSheet(
        title = stringResource(R.string.workout_detail_manage_layout, stringResource(layoutType.displayNameResId)),
        sections =
            listOf(
                ManagementSection(
                    title = stringResource(R.string.workout_detail_manage_items_section_title),
                    items =
                        itemConfigurations.sortedBy { it.position }.map { item ->
                            ManagementItem(
                                key = "detail_${item.itemId.name}",
                                label = stringResource(item.itemId.displayNameResId),
                                isVisible = item.isVisible,
                                supportedModes = emptyList(),
                                requestedMode = DashboardCardDisplayMode.VALUE,
                                onVisibilityChanged = { onItemVisibilityChanged(item.itemId, it) },
                                onDisplayModeChanged = {},
                            )
                        },
                ),
            ),
        onResetToDefaults = onResetToDefaults,
        onDismiss = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
    )
}
