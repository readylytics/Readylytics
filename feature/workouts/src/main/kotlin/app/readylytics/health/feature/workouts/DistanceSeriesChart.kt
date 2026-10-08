package app.readylytics.health.feature.workouts

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import app.readylytics.health.core.model.domain.preferences.UnitSystem
import app.readylytics.health.core.ui.components.DataPointTooltip
import app.readylytics.health.core.ui.components.DataPointTooltipData
import app.readylytics.health.core.ui.components.VicoChartTooltipOverlay
import app.readylytics.health.core.ui.R as CoreUiR

/** Selection + tooltip state shared by one chart's effects, semantics and gesture handling. */
@Stable
internal class DistanceChartSelection {
    var tooltip by mutableStateOf<DataPointTooltipData?>(null)
    var pointOffset by mutableStateOf<Offset?>(null)
    var index by mutableStateOf<Int?>(null)

    fun clearTooltip() {
        tooltip = null
        pointOffset = null
    }
}

internal data class DistanceSeriesUnits(
    val distance: String,
    val value: String,
)

private data class DistanceSeriesSemantics(
    val summary: String,
    val selection: String,
    val actions: List<CustomAccessibilityAction>,
)

private val DistanceSeriesKind.summaryRes: Int
    get() =
        when (this) {
            DistanceSeriesKind.PACE -> R.string.chart_accessibility_pace_summary
            DistanceSeriesKind.SPEED -> R.string.chart_accessibility_speed_summary
            DistanceSeriesKind.ELEVATION -> R.string.chart_accessibility_elevation_summary
        }

private val DistanceSeriesKind.selectedDescriptionRes: Int
    get() =
        when (this) {
            DistanceSeriesKind.PACE -> R.string.chart_accessibility_selected_pace
            DistanceSeriesKind.SPEED -> R.string.chart_accessibility_selected_speed
            DistanceSeriesKind.ELEVATION -> R.string.chart_accessibility_selected_elevation
        }

/** One distance-on-x line chart (pace, speed or elevation); see [DistanceSeriesKind]. */
@Composable
internal fun DistanceSeriesChart(
    chartData: List<Pair<Double, Double>>,
    kind: DistanceSeriesKind,
    unitSystem: UnitSystem,
    parentScrollInProgress: () -> Boolean = { false },
) {
    val points = remember(chartData, kind, unitSystem) { toDisplaySeries(chartData, kind, unitSystem) }
    val units = distanceSeriesUnits(kind, unitSystem)
    val selection = remember { DistanceChartSelection() }
    DistanceSeriesSelectionEffects(selection, points, kind, units, parentScrollInProgress)
    val semanticsState = rememberDistanceSeriesSemantics(selection, points, kind, units)
    val colors = MaterialTheme.colorScheme
    val lineColor = if (kind == DistanceSeriesKind.ELEVATION) colors.tertiary else colors.primary

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(kind.testTag)
                .semantics {
                    contentDescription = semanticsState.summary
                    stateDescription = semanticsState.selection
                    customActions = semanticsState.actions
                }.dismissTooltipOnMultiTouch(selection),
    ) {
        DistanceSeriesCartesianChart(points, kind, units, lineColor, selection)
        VicoChartTooltipOverlay(
            selectedPointOffset = selection.pointOffset,
            pulseColor = lineColor,
            modifier = Modifier.fillMaxWidth().height(DistanceChartHeight),
        )
        selection.tooltip?.let { data ->
            DataPointTooltip(
                isVisible = true,
                data = data,
                onDismissRequest = { selection.tooltip = null },
            )
        }
    }
}

@Composable
private fun distanceSeriesUnits(
    kind: DistanceSeriesKind,
    unitSystem: UnitSystem,
): DistanceSeriesUnits {
    val imperial = unitSystem == UnitSystem.IMPERIAL
    val distanceRes =
        if (imperial) R.string.workout_metric_distance_unit_mi else R.string.workout_metric_distance_unit_km
    val valueRes =
        when (kind) {
            DistanceSeriesKind.PACE ->
                if (imperial) R.string.workout_metric_pace_unit_min_mi else R.string.workout_metric_pace_unit_min_km
            DistanceSeriesKind.SPEED ->
                if (imperial) R.string.workout_metric_speed_unit_mph else R.string.workout_metric_speed_unit_kmh
            DistanceSeriesKind.ELEVATION ->
                if (imperial) R.string.workout_metric_elevation_unit_ft else R.string.workout_metric_elevation_unit_m
        }
    return DistanceSeriesUnits(distance = stringResource(distanceRes), value = stringResource(valueRes))
}

@Composable
private fun DistanceSeriesSelectionEffects(
    selection: DistanceChartSelection,
    points: List<Pair<Double, Double>>,
    kind: DistanceSeriesKind,
    units: DistanceSeriesUnits,
    parentScrollInProgress: () -> Boolean,
) {
    LaunchedEffect(selection.tooltip) {
        if (selection.tooltip == null) {
            selection.pointOffset = null
            selection.index = null
        }
    }
    LaunchedEffect(selection.index, points) {
        val point = selection.index?.let { points.getOrNull(it) }
        if (point == null) {
            selection.clearTooltip()
        } else {
            selection.tooltip =
                DataPointTooltipData(
                    valueText = formatPointValue(kind, point.second, units.value),
                    dateText = formatPointDistance(point.first, units.distance),
                    offset = selection.pointOffset?.let { IntOffset(it.x.toInt(), it.y.toInt()) } ?: IntOffset(0, 0),
                )
        }
    }
    val currentParentScrollInProgress by rememberUpdatedState(parentScrollInProgress)
    LaunchedEffect(Unit) {
        snapshotFlow { currentParentScrollInProgress() }.collect { inProgress ->
            if (inProgress) selection.clearTooltip()
        }
    }
}

@Composable
private fun rememberDistanceSeriesSemantics(
    selection: DistanceChartSelection,
    points: List<Pair<Double, Double>>,
    kind: DistanceSeriesKind,
    units: DistanceSeriesUnits,
): DistanceSeriesSemantics {
    val previousLabel = stringResource(CoreUiR.string.action_previous_point)
    val nextLabel = stringResource(CoreUiR.string.action_next_point)
    val clearLabel = stringResource(CoreUiR.string.action_clear_selection)
    val index = selection.index
    val actions =
        remember(index, points) {
            buildSelectionActions(selection, points.lastIndex, previousLabel, nextLabel, clearLabel)
        }
    val point = index?.let { points.getOrNull(it) }
    val selectedText =
        if (point != null) {
            stringResource(
                kind.selectedDescriptionRes,
                formatPointValue(kind, point.second, units.value),
                formatPointDistance(point.first, units.distance),
            )
        } else {
            stringResource(CoreUiR.string.chart_accessibility_no_selection)
        }
    return DistanceSeriesSemantics(stringResource(kind.summaryRes), selectedText, actions)
}

private fun buildSelectionActions(
    selection: DistanceChartSelection,
    lastIndex: Int,
    previousLabel: String,
    nextLabel: String,
    clearLabel: String,
): List<CustomAccessibilityAction> =
    buildList {
        if (lastIndex >= 0) {
            add(
                CustomAccessibilityAction(previousLabel) {
                    selection.index = previousPointIndex(selection.index, lastIndex)
                    true
                },
            )
            add(
                CustomAccessibilityAction(nextLabel) {
                    selection.index = nextPointIndex(selection.index, lastIndex)
                    true
                },
            )
        }
        if (selection.index != null) {
            add(
                CustomAccessibilityAction(clearLabel) {
                    selection.index = null
                    true
                },
            )
        }
    }

private fun Modifier.dismissTooltipOnMultiTouch(selection: DistanceChartSelection): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var isMultiTouch = false
            while (!isMultiTouch) {
                val event = awaitPointerEvent()
                if (event.changes.none { it.pressed }) break
                if (event.changes.size > 1) {
                    isMultiTouch = true
                    selection.clearTooltip()
                }
            }
        }
    }
