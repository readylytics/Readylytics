package app.readylytics.health.core.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.R
import app.readylytics.health.core.ui.components.reorder.DragController
import app.readylytics.health.domain.vitals.VitalsChartConfiguration
import app.readylytics.health.domain.vitals.VitalsChartId
import kotlin.math.roundToInt

/**
 * Single-column list that supports drag-and-drop reordering of vitals trend charts.
 *
 * Single-column counterpart to [ReorderableCardGrid]: every chart is full-width, one per
 * row, so the paired-row layout branch is absent. The controller mechanics are identical —
 * [DragController.pendingOrder] is the source of truth for order during a drag, all slot
 * bounds live in the root Column's local coordinate space, drag START is gated by the 48dp
 * drag-handle bounds, and a bottom "hide" drop zone reuses the delete-zone mechanism
 * (dropping hides the chart instead of removing it).
 */
@Immutable
data class ChartConfigurationsList(
    val items: List<VitalsChartConfiguration>,
)

@Immutable
data class ChartDataMap(
    val map: Map<VitalsChartId, @Composable (VitalsChartConfiguration) -> Unit>,
)

@Composable
fun ReorderableChartList(
    chartConfigurations: ChartConfigurationsList,
    chartDataMap: ChartDataMap,
    isEditing: Boolean,
    onChartHide: (VitalsChartId) -> Unit,
    onChartReorder: (List<VitalsChartConfiguration>) -> Unit,
    modifier: Modifier = Modifier,
    controller: DragController<VitalsChartId>? = null,
) {
    val items = chartConfigurations.items
    val dataMap = chartDataMap.map

    // Visible + renderable configs, keyed for O(1) lookup at render and drop time.
    val configByChartId: Map<VitalsChartId, VitalsChartConfiguration> =
        remember(items, dataMap.keys) {
            items
                .filter { it.isVisible && dataMap.containsKey(it.chartId) }
                .associateBy { it.chartId }
        }

    val dragController =
        remember {
            controller ?: DragController(
                items
                    .filter { it.isVisible && dataMap.containsKey(it.chartId) }
                    .sortedBy { it.position }
                    .map { it.chartId },
            )
        }

    // Sync controller from upstream when not actively dragging. Only the filtered + sorted
    // ids enter the controller so pendingOrder always matches what we actually render.
    LaunchedEffect(items, dataMap.keys) {
        val upstreamOrder =
            items
                .filter { it.isVisible && dataMap.containsKey(it.chartId) }
                .sortedBy { it.position }
                .map { it.chartId }
        dragController.syncFromUpstream(upstreamOrder)
    }

    // Render order is driven by the controller, not by upstream — gives the live drag preview.
    val displayableCharts: List<VitalsChartConfiguration> =
        dragController.pendingOrder
            .mapNotNull { configByChartId[it] }

    // Root coordinates for the list. All slot bounds are recorded relative to this so the
    // DragController operates in a single coordinate space.
    var rootCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var hideZoneTopPx by remember { mutableStateOf<Float?>(null) }

    // Bounds of each chart's drag-handle, in root-local space. Used only to decide where a
    // drag may START (gestures elsewhere on the chart never begin a drag).
    val handleBounds = remember { mutableStateMapOf<VitalsChartId, Rect>() }
    val hapticFeedback = LocalHapticFeedback.current

    val draggedId = dragController.draggedCardId

    val performDragEnd = {
        val result = dragController.onDragEnd()
        val draggedId = result.draggedId
        if (draggedId != null) {
            if (result.delete) {
                onChartHide(draggedId)
            } else {
                val updated =
                    result.finalOrder
                        .mapNotNull { id -> configByChartId[id] }
                        .mapIndexed { index, config -> config.copy(position = index) }
                onChartReorder(updated)
            }
        }
    }

    val onHandlePositioned: (VitalsChartId, LayoutCoordinates) -> Unit = { chartId, coords ->
        rootCoords?.let { root -> handleBounds[chartId] = root.localBoundingBoxOf(coords) }
    }

    // pointerInput(Unit) below never restarts across recomposition, so its closure would
    // otherwise capture stale performDragEnd/hideZoneTopPx from the composition it first ran
    // in. rememberUpdatedState keeps it reading the current values.
    val currentHideZoneTopPx by rememberUpdatedState(hideZoneTopPx)
    val currentPerformDragEnd by rememberUpdatedState(performDragEnd)

    Column(
        modifier =
            modifier
                .onGloballyPositioned { rootCoords = it }
                .then(
                    if (isEditing) {
                        Modifier.pointerInput(Unit) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { offset ->
                                    val targetChartId =
                                        handleBounds.entries
                                            .firstOrNull { (_, rect) -> rect.contains(offset) }
                                            ?.key
                                    if (targetChartId != null) {
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                        dragController.onDragStart(targetChartId)
                                    }
                                },
                                onDragEnd = { currentPerformDragEnd() },
                                onDragCancel = { currentPerformDragEnd() },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    if (dragController.draggedCardId != null) {
                                        dragController.onDrag(dragAmount, currentHideZoneTopPx)
                                    }
                                },
                            )
                        }
                    } else {
                        Modifier
                    },
                ),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        displayableCharts.forEach { chart ->
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .zIndex(if (draggedId == chart.chartId) 1f else 0f)
                        .onGloballyPositioned { coords ->
                            rootCoords?.let { root ->
                                dragController.updateSlotBounds(chart.chartId, root.localBoundingBoxOf(coords))
                            }
                        },
            ) {
                RenderChartItem(
                    chart = chart,
                    chartDataMap = dataMap,
                    isEditing = isEditing,
                    controller = dragController,
                    onHandlePositioned = onHandlePositioned,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // Hide drop zone at the bottom when editing. Reuses the delete-zone mechanism —
        // dropping here hides the chart (a reversible visibility toggle), not a removal.
        if (isEditing) {
            Spacer(modifier = Modifier.height(MaterialTheme.spacing.medium))
            val isHovered = dragController.hoveringDeleteZone
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .onGloballyPositioned { coords ->
                            rootCoords?.let { root ->
                                hideZoneTopPx = root.localBoundingBoxOf(coords).top
                            }
                        },
                color =
                    if (isHovered) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
                shape = MaterialTheme.shapes.large,
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.VisibilityOff,
                        contentDescription = null,
                        tint =
                            if (isHovered) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                    Spacer(modifier = Modifier.height(MaterialTheme.spacing.extraSmall))
                    Text(
                        text = stringResource(R.string.action_hide_drop_zone),
                        style = MaterialTheme.typography.labelSmall,
                        color =
                            if (isHovered) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun RenderChartItem(
    chart: VitalsChartConfiguration,
    chartDataMap: Map<VitalsChartId, @Composable (VitalsChartConfiguration) -> Unit>,
    isEditing: Boolean,
    controller: DragController<VitalsChartId>,
    onHandlePositioned: (VitalsChartId, LayoutCoordinates) -> Unit,
    modifier: Modifier,
) {
    // Keying the whole slot by chartId (rather than by loop position) keeps composition
    // identity stable across reorders and mode-only edits, so per-chart local state (e.g. a
    // chart's own tooltip selection) does not leak between sibling charts.
    key(chart.chartId) {
        val isDragged = controller.draggedCardId == chart.chartId
        val chartContent = chartDataMap[chart.chartId]!!

        ReorderableChartItem(
            chart = chart,
            content = { chartContent(chart) },
            isEditing = isEditing,
            isDragged = isDragged,
            controller = controller,
            onHandlePositioned = onHandlePositioned,
            modifier = modifier,
        )
    }
}

@Composable
private fun ReorderableChartItem(
    chart: VitalsChartConfiguration,
    content: @Composable () -> Unit,
    isEditing: Boolean,
    isDragged: Boolean,
    controller: DragController<VitalsChartId>,
    onHandlePositioned: (VitalsChartId, LayoutCoordinates) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .then(
                    if (isDragged) {
                        Modifier
                            .offset {
                                IntOffset(
                                    controller.dragOffset.x.roundToInt(),
                                    controller.dragOffset.y.roundToInt(),
                                )
                            }.graphicsLayer {
                                alpha = 0.9f
                                shadowElevation = 12.dp.toPx()
                                scaleX = 1.05f
                                scaleY = 1.05f
                            }
                    } else {
                        Modifier
                    },
                ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (isEditing) {
                            Modifier.padding(
                                horizontal = MaterialTheme.spacing.small,
                                vertical = MaterialTheme.spacing.extraSmall,
                            )
                        } else {
                            Modifier
                        },
                    ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isEditing) {
                // Drag gesture detection lives on the list's root Column (survives pendingOrder
                // swaps reparenting this chart mid-drag). This Box only reports its own bounds so
                // the root's hit test can restrict drag START to this 48dp handle, keeping taps
                // elsewhere on the chart from starting a drag.
                val dragHandleDescription = stringResource(R.string.accessibility_drag_to_reorder)
                Box(
                    modifier =
                        Modifier
                            .size(48.dp)
                            .semantics { contentDescription = dragHandleDescription }
                            .onGloballyPositioned { coords -> onHandlePositioned(chart.chartId, coords) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DragIndicator,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                content()
            }
        }
    }
}
