package app.readylytics.health.feature.workouts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.components.EditModeFab
import app.readylytics.health.core.ui.components.rememberManageLayoutState
import app.readylytics.health.core.ui.components.reorder.ReorderableGrid
import app.readylytics.health.domain.workouts.detail.WorkoutDetailItemCatalog
import app.readylytics.health.domain.workouts.detail.WorkoutDetailItemConfiguration
import app.readylytics.health.domain.workouts.detail.WorkoutDetailItemId
import app.readylytics.health.feature.workouts.R
import app.readylytics.health.core.ui.R as CoreUiR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutDetailRoute(
    workoutId: String,
    onBack: () -> Unit,
    onRequestRoutePermission: (onGranted: () -> Unit) -> Unit = {},
    viewModel: WorkoutDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(workoutId) {
        viewModel.loadWorkout(workoutId)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.label_workout_details)) },
                windowInsets = WindowInsets(0),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(CoreUiR.string.back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        if (uiState.isLoading) {
            Box(Modifier.padding(innerPadding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            WorkoutDetailScreen(
                uiState = uiState,
                onGrantPermissionClick = {
                    onRequestRoutePermission {
                        viewModel.onRoutePermissionResult()
                    }
                },
                onToggleLayoutManagement = viewModel::onToggleLayoutManagement,
                onCancelLayoutManagement = viewModel::onCancelLayoutManagement,
                onToggleItemVisibility = viewModel::onToggleItemVisibility,
                onReorderItems = viewModel::onReorderItems,
                onResetLayoutToDefaults = viewModel::onResetLayoutToDefaults,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutDetailScreen(
    uiState: WorkoutDetailUiState,
    onGrantPermissionClick: () -> Unit = {},
    onToggleLayoutManagement: () -> Unit = {},
    onCancelLayoutManagement: () -> Unit = {},
    onToggleItemVisibility: (WorkoutDetailItemId, Boolean) -> Unit = { _, _ -> },
    onReorderItems: (List<WorkoutDetailItemConfiguration>) -> Unit = {},
    onResetLayoutToDefaults: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val workout = uiState.workout ?: return
    val scrollState = rememberScrollState()
    val manageState = rememberManageLayoutState()

    val itemDataMap =
        remember(uiState, onGrantPermissionClick) {
            buildWorkoutDetailItemDataMap(
                uiState = uiState,
                onGrantPermissionClick = onGrantPermissionClick,
                parentScrollInProgress = { scrollState.isScrollInProgress },
            )
        }

    val available =
        remember(uiState) {
            WorkoutDetailItemAvailability.available(WorkoutDetailItemAvailability.inputFrom(uiState))
        }

    val renderedConfigs =
        remember(uiState.itemConfigurations, uiState.isManagingLayout, available) {
            uiState.itemConfigurations
                .sortedBy { it.position }
                .filter { it.isVisible && (uiState.isManagingLayout || it.itemId in available) }
        }

    val displayDataMap =
        remember(itemDataMap, uiState.isManagingLayout, available) {
            itemDataMap.withPlaceholders(uiState.isManagingLayout, available)
        }

    Box(modifier = modifier.fillMaxSize()) {
        if (manageState.isManageOpen) {
            WorkoutDetailManagementBottomSheet(
                layoutType = uiState.layoutType,
                itemConfigurations = uiState.itemConfigurations,
                onItemVisibilityChanged = onToggleItemVisibility,
                onResetToDefaults = onResetLayoutToDefaults,
                onDismiss = manageState.closeManage,
                sheetState = manageState.sheetState,
            )
        }

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(MaterialTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            WorkoutDetailHeader(workout)

            ReorderableGrid(
                items = renderedConfigs,
                dataMap = displayDataMap,
                isEditing = uiState.isManagingLayout,
                onItemReorder = onReorderItems,
                onItemDropToRemove = { itemId -> onToggleItemVisibility(itemId, false) },
                fullWidthIds = WorkoutDetailItemCatalog.FULL_WIDTH_ITEMS,
            )

            if (!uiState.isManagingLayout) {
                FilledTonalButton(
                    onClick = onToggleLayoutManagement,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = MaterialTheme.spacing.pageSectionGap),
                    colors =
                        ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                ) {
                    Text(
                        text = stringResource(CoreUiR.string.action_customize),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }

        EditModeFab(
            isVisible = uiState.isManagingLayout,
            onDoneClick = onToggleLayoutManagement,
            onCancelClick = onCancelLayoutManagement,
            onManageClick = manageState.openManage,
            modifier = Modifier.align(Alignment.BottomEnd).padding(MaterialTheme.spacing.pageHorizontal),
        )
    }
}

/**
 * In edit mode, ensures every detail item has a renderer in the data map so unavailable items
 * stay draggable: real renderers for available items, a [WorkoutDetailItemPlaceholder] for the
 * rest. Outside edit mode the map is returned unchanged and the grid drops missing ids.
 */
private fun Map<WorkoutDetailItemId, @Composable (WorkoutDetailItemConfiguration) -> Unit>.withPlaceholders(
    isManagingLayout: Boolean,
    available: Set<WorkoutDetailItemId>,
): Map<WorkoutDetailItemId, @Composable (WorkoutDetailItemConfiguration) -> Unit> =
    if (!isManagingLayout) {
        this
    } else {
        buildMap {
            WorkoutDetailItemId.entries.forEach { id ->
                val renderer = this@withPlaceholders[id]
                put(
                    id,
                    if (renderer != null && id in available) {
                        renderer
                    } else {
                        { WorkoutDetailItemPlaceholder(label = stringResource(id.displayNameResId)) }
                    },
                )
            }
        }
    }
