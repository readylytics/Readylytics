package app.readylytics.health.feature.workouts

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.model.domain.workouts.FatigueCurvePoint
import app.readylytics.health.core.model.domain.workouts.FatigueCurveRange
import app.readylytics.health.core.ui.common.SkeletonCard
import app.readylytics.health.core.ui.components.ChartDefaults
import app.readylytics.health.core.ui.components.DataPointTooltip
import app.readylytics.health.core.ui.components.DataPointTooltipData
import app.readylytics.health.core.ui.components.InvisibleMarker
import app.readylytics.health.core.ui.components.VicoChartTooltipOverlay
import app.readylytics.health.core.ui.components.rememberChartMarkerVisibilityListener
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarkerVisibilityListener
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import app.readylytics.health.core.ui.R as CoreUiR

@Composable
fun ResidualFatigueCurveChart(
    points: List<FatigueCurvePoint>,
    range: FatigueCurveRange = FatigueCurveRange.ONE_DAY,
    isLoading: Boolean = false,
    modifier: Modifier = Modifier,
    parentScrollInProgress: () -> Boolean = { false },
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.padding(MaterialTheme.spacing.medium)) {
            if (isLoading && points.isEmpty()) {
                SkeletonCard(height = 180.dp, modifier = Modifier.fillMaxWidth())
            } else if (points.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.chart_residual_fatigue_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                ResidualFatigueChartContent(
                    points = points,
                    range = range,
                    parentScrollInProgress = parentScrollInProgress,
                )
            }
        }
    }
}

@Composable
private fun ResidualFatigueChartContent(
    points: List<FatigueCurvePoint>,
    range: FatigueCurveRange,
    parentScrollInProgress: () -> Boolean = { false },
) {
    var tooltipState by remember { mutableStateOf<DataPointTooltipData?>(null) }
    var selectedPointOffset by remember { mutableStateOf<Offset?>(null) }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    val tooltipFormat = stringResource(R.string.chart_residual_fatigue_tooltip_format)

    ObserveParentScroll(parentScrollInProgress) {
        tooltipState = null
        selectedPointOffset = null
        selectedIndex = null
    }

    ObserveTooltipSelection(
        selection =
            ResidualFatigueSelectionData(
                selectedIndex = selectedIndex,
                selectedPointOffset = selectedPointOffset,
                points = points,
                range = range,
                tooltipFormat = tooltipFormat,
            ),
        onUpdateTooltip = { tooltipState = it },
    )

    val overlayState =
        rememberResidualFatigueOverlayState(
            selectedIndex = selectedIndex,
            points = points,
            range = range,
            tooltipFormat = tooltipFormat,
            pointOffset = selectedPointOffset,
            tooltip = tooltipState,
            onSelectIndex = { selectedIndex = it },
        )

    val markerListener =
        rememberChartMarkerVisibilityListener(
            onPointSelected = { x, _, canvasX, canvasY ->
                selectedPointOffset = Offset(canvasX, canvasY)
                selectedIndex = points.indices.minByOrNull { abs(points[it].timeMinutesFromStart - x) } ?: 0
            },
        )

    ResidualFatigueChartBox(
        points = points,
        range = range,
        state = overlayState,
        markerListener = markerListener,
        onMultiTouch = {
            tooltipState = null
            selectedPointOffset = null
        },
        onDismissTooltip = { tooltipState = null },
    )
}

@Composable
private fun rememberResidualFatigueOverlayState(
    selectedIndex: Int?,
    points: List<FatigueCurvePoint>,
    range: FatigueCurveRange,
    tooltipFormat: String,
    pointOffset: Offset?,
    tooltip: DataPointTooltipData?,
    onSelectIndex: (Int?) -> Unit,
): ResidualFatigueOverlayState {
    val customActionsList =
        rememberResidualFatigueAccessibilityActions(
            selectedIndex = selectedIndex,
            points = points,
            onSelectIndex = onSelectIndex,
        )
    val chartSummary = stringResource(R.string.chart_residual_fatigue_curve_description)
    val selectedValueDesc =
        selectedIndex?.let { idx ->
            points.getOrNull(idx)?.let { formatFatiguePoint(it, range, tooltipFormat) }
        } ?: stringResource(CoreUiR.string.chart_accessibility_no_selection)

    return ResidualFatigueOverlayState(
        summary = chartSummary,
        selectedValueDesc = selectedValueDesc,
        actions = customActionsList,
        pointOffset = pointOffset,
        tooltip = tooltip,
    )
}

@Composable
private fun ResidualFatigueChartBox(
    points: List<FatigueCurvePoint>,
    range: FatigueCurveRange,
    state: ResidualFatigueOverlayState,
    markerListener: CartesianMarkerVisibilityListener,
    onMultiTouch: () -> Unit,
    onDismissTooltip: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("ResidualFatigueCurveChartCanvas")
                .semantics {
                    contentDescription = state.summary
                    stateDescription = state.selectedValueDesc
                    customActions = state.actions
                }.pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val isMulti = event.changes.size > 1
                            if (isMulti) {
                                onMultiTouch()
                            }
                        } while (!isMulti && event.changes.any { it.pressed })
                    }
                },
    ) {
        ResidualFatigueCartesianHost(points = points, range = range, markerListener = markerListener)
        VicoChartTooltipOverlay(
            selectedPointOffset = state.pointOffset,
            pulseColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth().height(200.dp),
        )
        if (state.tooltip != null) {
            DataPointTooltip(
                isVisible = true,
                data = state.tooltip,
                onDismissRequest = onDismissTooltip,
            )
        }
    }
}

@Composable
private fun ResidualFatigueCartesianHost(
    points: List<FatigueCurvePoint>,
    range: FatigueCurveRange,
    markerListener: CartesianMarkerVisibilityListener,
) {
    val modelProducer = remember { CartesianChartModelProducer() }
    LaunchedEffect(points) {
        if (points.isNotEmpty()) {
            modelProducer.runTransaction {
                lineModel {
                    series(
                        x = points.map { it.timeMinutesFromStart.toDouble() },
                        y = points.map { it.fatigueValue.toDouble() },
                    )
                }
            }
        }
    }

    val peak = points.map { it.fatigueValue }.maxOrNull() ?: 0f
    val maxY = maxOf(100.0, ceil(peak.toDouble() / Y_AXIS_GRID_STEP) * Y_AXIS_GRID_STEP)
    val maxX = range.days * TOTAL_MINUTES_IN_DAY
    val rangeProvider =
        remember(points, range) {
            CartesianLayerRangeProvider.fixed(minX = 0.0, maxX = maxX, minY = 0.0, maxY = maxY)
        }
    val lineLayer = rememberResidualFatigueLineLayer(rangeProvider)
    val itemPlacer = rememberResidualFatigueItemPlacer(range)
    val valueFormatter = rememberResidualFatigueValueFormatter(range, points)
    val label = ChartDefaults.labelTextComponent()
    val axisLabel = ChartDefaults.axisLabelTextComponent()
    val guideline = ChartDefaults.guidelineComponent()

    CartesianChartHost(
        chart =
            rememberCartesianChart(
                lineLayer,
                startAxis =
                    VerticalAxis.rememberStart(
                        label = label,
                        titleComponent = axisLabel,
                        guideline = guideline,
                        valueFormatter = CartesianValueFormatter { _, v, _ -> v.roundToInt().toString() },
                    ),
                bottomAxis =
                    HorizontalAxis.rememberBottom(
                        label = label,
                        titleComponent = axisLabel,
                        guideline = guideline,
                        valueFormatter = valueFormatter,
                        itemPlacer = itemPlacer,
                    ),
                marker = InvisibleMarker,
                markerVisibilityListener = markerListener,
            ),
        modelProducer = modelProducer,
        zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.Content),
        modifier = Modifier.fillMaxWidth().height(200.dp),
    )
}
