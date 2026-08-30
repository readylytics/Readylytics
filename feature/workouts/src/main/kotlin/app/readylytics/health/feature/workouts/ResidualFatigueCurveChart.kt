package app.readylytics.health.feature.workouts

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.model.domain.workouts.FatigueCurvePoint
import app.readylytics.health.core.ui.common.SkeletonCard
import app.readylytics.health.core.ui.components.ChartDefaults
import app.readylytics.health.core.ui.components.DataPointTooltip
import app.readylytics.health.core.ui.components.DataPointTooltipData
import app.readylytics.health.core.ui.components.InvisibleMarker
import app.readylytics.health.core.ui.components.MetricTooltip
import app.readylytics.health.core.ui.components.VicoChartTooltipOverlay
import app.readylytics.health.core.ui.components.rememberChartMarkerVisibilityListener
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarkerVisibilityListener
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.Fill
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import app.readylytics.health.core.ui.R as CoreUiR

private const val TOTAL_MINUTES_IN_DAY = 1440.0
private const val CHART_CUBIC_INTERPOLATION = 0.2f
private const val GRADIENT_START_ALPHA = 0.35f
private const val Y_AXIS_GRID_STEP = 25.0
private const val MINUTES_PER_HOUR = 60
private const val HOURS_PER_DAY = 24

private data class ResidualFatigueOverlayState(
    val summary: String,
    val selectedValueDesc: String,
    val actions: List<CustomAccessibilityAction>,
    val pointOffset: Offset?,
    val tooltip: DataPointTooltipData?,
)

@Composable
fun ResidualFatigueCurveChart(
    points: List<FatigueCurvePoint>,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
    parentScrollInProgress: () -> Boolean = { false },
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.padding(MaterialTheme.spacing.medium)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.chart_residual_fatigue_curve_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                MetricTooltip(
                    description = stringResource(R.string.chart_residual_fatigue_curve_description),
                    iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(MaterialTheme.spacing.medium))

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
                    parentScrollInProgress = parentScrollInProgress,
                )
            }
        }
    }
}

private fun formatFatiguePoint(
    point: FatigueCurvePoint,
    tooltipFormat: String,
): String {
    val hours = (point.timeMinutesFromStart / MINUTES_PER_HOUR).toInt().coerceIn(0, HOURS_PER_DAY)
    val minutes = (point.timeMinutesFromStart % MINUTES_PER_HOUR).toInt().coerceIn(0, MINUTES_PER_HOUR - 1)
    val timeStr = String.format(Locale.getDefault(), "%02d:%02d", hours, minutes)
    return String.format(Locale.getDefault(), tooltipFormat, timeStr, point.fatigueValue)
}

@Composable
private fun rememberResidualFatigueItemPlacer(): HorizontalAxis.ItemPlacer {
    val labels = remember { listOf(0.0, 240.0, 480.0, 720.0, 960.0, 1200.0, 1440.0) }
    return remember(labels) {
        val base = HorizontalAxis.ItemPlacer.aligned(spacing = { 1 }, addExtremeLabelPadding = true)
        object : HorizontalAxis.ItemPlacer by base {
            override fun getLabelValues(
                context: CartesianDrawingContext,
                visibleXRange: ClosedFloatingPointRange<Double>,
                fullXRange: ClosedFloatingPointRange<Double>,
                maxLabelWidth: Float,
            ): List<Double> = labels.filter { it in fullXRange }
        }
    }
}

@Composable
private fun rememberResidualFatigueAccessibilityActions(
    selectedIndex: Int?,
    points: List<FatigueCurvePoint>,
    onSelectIndex: (Int?) -> Unit,
): List<CustomAccessibilityAction> {
    val prevLabel = stringResource(CoreUiR.string.action_previous_point)
    val nextLabel = stringResource(CoreUiR.string.action_next_point)
    val clearLabel = stringResource(CoreUiR.string.action_clear_selection)

    return remember(selectedIndex, points) {
        val list = mutableListOf<CustomAccessibilityAction>()
        if (points.isNotEmpty()) {
            list.add(
                CustomAccessibilityAction(prevLabel) {
                    val curr = selectedIndex ?: -1
                    onSelectIndex(if (curr > 0) curr - 1 else points.lastIndex)
                    true
                },
            )
            list.add(
                CustomAccessibilityAction(nextLabel) {
                    val curr = selectedIndex ?: -1
                    onSelectIndex(if (curr != -1 && curr < points.lastIndex) curr + 1 else 0)
                    true
                },
            )
        }
        if (selectedIndex != null) {
            list.add(
                CustomAccessibilityAction(clearLabel) {
                    onSelectIndex(null)
                    true
                },
            )
        }
        list
    }
}

@Composable
private fun ResidualFatigueChartContent(
    points: List<FatigueCurvePoint>,
    parentScrollInProgress: () -> Boolean = { false },
) {
    var tooltipState by remember { mutableStateOf<DataPointTooltipData?>(null) }
    var selectedPointOffset by remember { mutableStateOf<Offset?>(null) }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    val tooltipFormat = stringResource(R.string.chart_residual_fatigue_tooltip_format)

    ObserveScrollAndSelection(
        parentScrollInProgress = parentScrollInProgress,
        selectedIndex = selectedIndex,
        selectedPointOffset = selectedPointOffset,
        points = points,
        tooltipFormat = tooltipFormat,
        onUpdateTooltip = { tooltipState = it },
        onResetSelection = {
            tooltipState = null
            selectedPointOffset = null
            selectedIndex = null
        },
    )

    val customActionsList =
        rememberResidualFatigueAccessibilityActions(
            selectedIndex = selectedIndex,
            points = points,
            onSelectIndex = { selectedIndex = it },
        )
    val chartSummary = stringResource(R.string.chart_residual_fatigue_curve_description)
    val selectedValueDesc =
        selectedIndex?.let { idx ->
            points.getOrNull(idx)?.let { formatFatiguePoint(it, tooltipFormat) }
        } ?: stringResource(CoreUiR.string.chart_accessibility_no_selection)

    val markerListener =
        rememberChartMarkerVisibilityListener(
            onPointSelected = { x, _, canvasX, canvasY ->
                selectedPointOffset = Offset(canvasX, canvasY)
                selectedIndex = points.indices.minByOrNull { abs(points[it].timeMinutesFromStart - x) } ?: 0
            },
        )

    val overlayState =
        ResidualFatigueOverlayState(
            summary = chartSummary,
            selectedValueDesc = selectedValueDesc,
            actions = customActionsList,
            pointOffset = selectedPointOffset,
            tooltip = tooltipState,
        )

    ResidualFatigueChartBox(
        points = points,
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
private fun ResidualFatigueChartBox(
    points: List<FatigueCurvePoint>,
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
        ResidualFatigueCartesianHost(points = points, markerListener = markerListener)
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
private fun ObserveScrollAndSelection(
    parentScrollInProgress: () -> Boolean,
    selectedIndex: Int?,
    selectedPointOffset: Offset?,
    points: List<FatigueCurvePoint>,
    tooltipFormat: String,
    onUpdateTooltip: (DataPointTooltipData?) -> Unit,
    onResetSelection: () -> Unit,
) {
    LaunchedEffect(selectedIndex, points, selectedPointOffset) {
        val idx = selectedIndex
        if (idx != null && idx in points.indices) {
            val point = points[idx]
            val formatted = formatFatiguePoint(point, tooltipFormat)
            val offset =
                selectedPointOffset?.let {
                    IntOffset(it.x.toInt(), it.y.toInt())
                } ?: IntOffset(0, 0)
            onUpdateTooltip(DataPointTooltipData(valueText = formatted, dateText = "", offset = offset))
        } else {
            onUpdateTooltip(null)
        }
    }

    val currentParentScrollInProgress by rememberUpdatedState(parentScrollInProgress)
    LaunchedEffect(Unit) {
        snapshotFlow { currentParentScrollInProgress() }.collect { inProgress ->
            if (inProgress) onResetSelection()
        }
    }
}

@Composable
private fun rememberResidualFatigueLineLayer(rangeProvider: CartesianLayerRangeProvider): LineCartesianLayer {
    val primaryColor = MaterialTheme.colorScheme.primary
    val lineFill = LineCartesianLayer.LineFill.single(Fill(primaryColor))
    val areaFill =
        LineCartesianLayer.AreaFill.single(
            Fill(
                Brush.verticalGradient(
                    listOf(
                        primaryColor.copy(alpha = GRADIENT_START_ALPHA),
                        primaryColor.copy(alpha = 0.0f),
                    ),
                ),
            ),
        )
    return rememberLineCartesianLayer(
        lineProvider =
            LineCartesianLayer.LineProvider.series(
                LineCartesianLayer.rememberLine(
                    fill = lineFill,
                    areaFill = areaFill,
                    interpolator = LineCartesianLayer.Interpolator.cubic(CHART_CUBIC_INTERPOLATION),
                ),
            ),
        rangeProvider = rangeProvider,
    )
}

@Composable
private fun ResidualFatigueCartesianHost(
    points: List<FatigueCurvePoint>,
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
    val rangeProvider =
        remember(points) {
            CartesianLayerRangeProvider.fixed(minX = 0.0, maxX = TOTAL_MINUTES_IN_DAY, minY = 0.0, maxY = maxY)
        }
    val lineLayer = rememberResidualFatigueLineLayer(rangeProvider)
    val itemPlacer = rememberResidualFatigueItemPlacer()
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
                        valueFormatter =
                            CartesianValueFormatter { _, v, _ ->
                                val h = (v / MINUTES_PER_HOUR.toDouble()).roundToInt().coerceIn(0, HOURS_PER_DAY)
                                String.format(Locale.getDefault(), "%02d:00", h)
                            },
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
