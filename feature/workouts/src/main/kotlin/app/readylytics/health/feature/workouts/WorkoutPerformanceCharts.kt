package app.readylytics.health.feature.workouts

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import app.readylytics.health.core.ui.components.ChartDefaults
import app.readylytics.health.core.ui.components.DataPointTooltip
import app.readylytics.health.core.ui.components.DataPointTooltipData
import app.readylytics.health.core.ui.components.InvisibleMarker
import app.readylytics.health.core.ui.components.VicoChartTooltipOverlay
import app.readylytics.health.core.ui.components.rememberChartMarkerVisibilityListener
import app.readylytics.health.domain.preferences.UnitSystem
import app.readylytics.health.domain.util.UnitConverter
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
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.Fill
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import app.readylytics.health.core.ui.R as CoreUiR

@Composable
fun WorkoutPerformanceCharts(
    paceSpeedData: List<Pair<Double, Double>>,
    elevationData: List<Pair<Double, Double>>,
    isPaceMode: Boolean,
    modifier: Modifier = Modifier,
    unitSystem: UnitSystem = UnitSystem.METRIC,
    parentScrollInProgress: () -> Boolean = { false },
) {
    if (paceSpeedData.isEmpty() && elevationData.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        if (paceSpeedData.isNotEmpty()) {
            PaceSpeedChartCard(
                chartData = paceSpeedData,
                isPaceMode = isPaceMode,
                unitSystem = unitSystem,
                parentScrollInProgress = parentScrollInProgress,
            )
        }
        if (elevationData.isNotEmpty()) {
            ElevationChartCard(
                chartData = elevationData,
                unitSystem = unitSystem,
                parentScrollInProgress = parentScrollInProgress,
            )
        }
    }
}

@Composable
fun PaceSpeedChartCard(
    chartData: List<Pair<Double, Double>>,
    isPaceMode: Boolean,
    modifier: Modifier = Modifier,
    unitSystem: UnitSystem = UnitSystem.METRIC,
    parentScrollInProgress: () -> Boolean = { false },
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        Column(Modifier.padding(MaterialTheme.spacing.medium)) {
            val title =
                if (isPaceMode) {
                    stringResource(R.string.workout_chart_pace_title)
                } else {
                    stringResource(R.string.workout_chart_speed_title)
                }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(MaterialTheme.spacing.medium))
            PaceSpeedChart(
                chartData = chartData,
                isPaceMode = isPaceMode,
                unitSystem = unitSystem,
                parentScrollInProgress = parentScrollInProgress,
            )
        }
    }
}

@Composable
private fun PaceSpeedChart(
    chartData: List<Pair<Double, Double>>,
    isPaceMode: Boolean,
    unitSystem: UnitSystem,
    parentScrollInProgress: () -> Boolean = { false },
) {
    var tooltipState by remember { mutableStateOf<DataPointTooltipData?>(null) }
    var selectedPointOffset by remember { mutableStateOf<Offset?>(null) }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }

    val displayData =
        remember(chartData, isPaceMode, unitSystem) {
            chartData.map { (distKm, paceOrSpeed) ->
                val dist =
                    if (unitSystem == UnitSystem.IMPERIAL) {
                        distKm * UnitConverter.KM_TO_MI.toDouble()
                    } else {
                        distKm
                    }
                val convertedVal =
                    if (unitSystem == UnitSystem.IMPERIAL) {
                        if (isPaceMode) {
                            paceOrSpeed * UnitConverter.MI_PER_KM.toDouble()
                        } else {
                            paceOrSpeed * UnitConverter.KM_TO_MI.toDouble()
                        }
                    } else {
                        paceOrSpeed
                    }
                (kotlin.math.round(dist * 1000.0) / 1000.0) to convertedVal
            }
        }

    val distanceUnit =
        if (unitSystem == UnitSystem.IMPERIAL) {
            stringResource(R.string.workout_metric_distance_unit_mi)
        } else {
            stringResource(R.string.workout_metric_distance_unit_km)
        }
    val yAxisUnit =
        if (isPaceMode) {
            if (unitSystem == UnitSystem.IMPERIAL) {
                stringResource(R.string.workout_metric_pace_unit_min_mi)
            } else {
                stringResource(R.string.workout_metric_pace_unit_min_km)
            }
        } else {
            if (unitSystem == UnitSystem.IMPERIAL) {
                stringResource(R.string.workout_metric_speed_unit_mph)
            } else {
                stringResource(R.string.workout_metric_speed_unit_kmh)
            }
        }

    LaunchedEffect(tooltipState) {
        if (tooltipState == null) {
            selectedPointOffset = null
            selectedIndex = null
        }
    }

    LaunchedEffect(selectedIndex, displayData) {
        val idx = selectedIndex
        if (idx != null && idx in displayData.indices) {
            val point = displayData[idx]
            val distStr = String.format(Locale.getDefault(), "%.2f %s", point.first, distanceUnit)
            val valStr =
                if (isPaceMode) {
                    val min = point.second.toInt()
                    val sec = ((point.second - min) * 60).roundToInt().coerceIn(0, 59)
                    String.format(Locale.getDefault(), "%d:%02d %s", min, sec, yAxisUnit)
                } else {
                    String.format(Locale.getDefault(), "%.1f %s", point.second, yAxisUnit)
                }
            tooltipState =
                DataPointTooltipData(
                    valueText = valStr,
                    dateText = distStr,
                    offset =
                        selectedPointOffset?.let {
                            IntOffset(it.x.toInt(), it.y.toInt())
                        } ?: IntOffset(0, 0),
                )
        } else {
            tooltipState = null
            selectedPointOffset = null
        }
    }

    val currentParentScrollInProgress by rememberUpdatedState(parentScrollInProgress)
    LaunchedEffect(Unit) {
        snapshotFlow { currentParentScrollInProgress() }.collect { inProgress ->
            if (inProgress) {
                tooltipState = null
                selectedPointOffset = null
            }
        }
    }

    val modelProducer = remember { CartesianChartModelProducer() }

    LaunchedEffect(displayData) {
        if (displayData.isEmpty()) return@LaunchedEffect
        modelProducer.runTransaction {
            lineModel {
                series(
                    x = displayData.map { it.first },
                    y = displayData.map { it.second },
                )
            }
        }
    }

    val maxDistance = displayData.lastOrNull()?.first ?: 0.0
    val distanceLabels = remember(maxDistance) { computeDistanceLabels(maxDistance, 5) }

    val itemPlacer =
        remember(distanceLabels) {
            val base =
                HorizontalAxis.ItemPlacer.aligned(
                    spacing = { 1 },
                    addExtremeLabelPadding = true,
                )
            object : HorizontalAxis.ItemPlacer by base {
                override fun getLabelValues(
                    context: CartesianDrawingContext,
                    visibleXRange: ClosedFloatingPointRange<Double>,
                    fullXRange: ClosedFloatingPointRange<Double>,
                    maxLabelWidth: Float,
                ): List<Double> = distanceLabels.filter { it in fullXRange }
            }
        }

    val rangeProvider =
        remember(displayData, isPaceMode, maxDistance) {
            val values = displayData.map { it.second }
            val lo = values.minOrNull() ?: 0.0
            val hi = values.maxOrNull() ?: 0.0
            val minMargin = if (isPaceMode) 0.5 else 1.0
            CartesianLayerRangeProvider.fixed(
                minX = 0.0,
                maxX = maxDistance.coerceAtLeast(0.1),
                minY = (lo - minMargin).coerceAtLeast(0.0),
                maxY = hi + minMargin,
            )
        }

    val labelComponent = ChartDefaults.labelTextComponent()
    val axisLabelComponent = ChartDefaults.axisLabelTextComponent()
    val guidelineComponent = ChartDefaults.guidelineComponent()

    val markerVisibilityListener =
        rememberChartMarkerVisibilityListener(
            onPointSelected = { x, _, canvasX, canvasY ->
                selectedPointOffset = Offset(canvasX, canvasY)
                val idx =
                    displayData.indices.minByOrNull { abs(displayData[it].first - x) } ?: 0
                selectedIndex = idx
            },
        )

    val prevActionLabel = stringResource(CoreUiR.string.action_previous_point)
    val nextActionLabel = stringResource(CoreUiR.string.action_next_point)
    val clearActionLabel = stringResource(CoreUiR.string.action_clear_selection)

    val customActionsList =
        remember(selectedIndex, displayData) {
            val list = mutableListOf<CustomAccessibilityAction>()
            if (displayData.isNotEmpty()) {
                list.add(
                    CustomAccessibilityAction(prevActionLabel) {
                        val curr = selectedIndex ?: -1
                        selectedIndex = if (curr > 0) curr - 1 else displayData.lastIndex
                        true
                    },
                )
                list.add(
                    CustomAccessibilityAction(nextActionLabel) {
                        val curr = selectedIndex ?: -1
                        selectedIndex = if (curr != -1 && curr < displayData.lastIndex) curr + 1 else 0
                        true
                    },
                )
            }
            if (selectedIndex != null) {
                list.add(
                    CustomAccessibilityAction(clearActionLabel) {
                        selectedIndex = null
                        true
                    },
                )
            }
            list
        }

    val chartSummary =
        if (isPaceMode) {
            stringResource(R.string.chart_accessibility_pace_summary)
        } else {
            stringResource(R.string.chart_accessibility_speed_summary)
        }
    val selectedValueDescription =
        selectedIndex?.let { idx ->
            val point = displayData.getOrNull(idx)
            if (point != null) {
                val distStr = String.format(Locale.getDefault(), "%.2f %s", point.first, distanceUnit)
                val valStr =
                    if (isPaceMode) {
                        val min = point.second.toInt()
                        val sec = ((point.second - min) * 60).roundToInt().coerceIn(0, 59)
                        String.format(Locale.getDefault(), "%d:%02d %s", min, sec, yAxisUnit)
                    } else {
                        String.format(Locale.getDefault(), "%.1f %s", point.second, yAxisUnit)
                    }
                if (isPaceMode) {
                    stringResource(R.string.chart_accessibility_selected_pace, valStr, distStr)
                } else {
                    stringResource(R.string.chart_accessibility_selected_speed, valStr, distStr)
                }
            } else {
                null
            }
        } ?: stringResource(CoreUiR.string.chart_accessibility_no_selection)

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("PaceSpeedChartCanvas")
                .semantics {
                    contentDescription = chartSummary
                    stateDescription = selectedValueDescription
                    customActions = customActionsList
                }.pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var isMultiTouch = false
                        while (!isMultiTouch) {
                            val event = awaitPointerEvent()
                            if (event.changes.none { it.pressed }) break
                            if (event.changes.size > 1) {
                                isMultiTouch = true
                                tooltipState = null
                                selectedPointOffset = null
                            }
                        }
                    }
                },
    ) {
        CartesianChartHost(
            chart =
                rememberCartesianChart(
                    rememberLineCartesianLayer(
                        lineProvider =
                            LineCartesianLayer.LineProvider.series(
                                LineCartesianLayer.rememberLine(
                                    fill = LineCartesianLayer.LineFill.single(Fill(MaterialTheme.colorScheme.primary)),
                                    areaFill =
                                        LineCartesianLayer.AreaFill.single(
                                            Fill(
                                                brush =
                                                    Brush.verticalGradient(
                                                        colors =
                                                            listOf(
                                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.0f),
                                                            ),
                                                    ),
                                            ),
                                        ),
                                    interpolator = LineCartesianLayer.Interpolator.cubic(0.2f),
                                ),
                            ),
                        rangeProvider = rangeProvider,
                    ),
                    startAxis =
                        VerticalAxis.rememberStart(
                            label = labelComponent,
                            title = { yAxisUnit },
                            titleComponent = axisLabelComponent,
                            guideline = guidelineComponent,
                            valueFormatter =
                                CartesianValueFormatter { _, value, _ ->
                                    if (isPaceMode) {
                                        val min = value.toInt()
                                        val sec = ((value - min) * 60).roundToInt().coerceIn(0, 59)
                                        String.format(Locale.getDefault(), "%d:%02d", min, sec)
                                    } else {
                                        String.format(Locale.getDefault(), "%.1f", value)
                                    }
                                },
                        ),
                    bottomAxis =
                        HorizontalAxis.rememberBottom(
                            label = labelComponent,
                            title = { distanceUnit },
                            titleComponent = axisLabelComponent,
                            guideline = guidelineComponent,
                            valueFormatter =
                                CartesianValueFormatter { _, value, _ ->
                                    if (maxDistance < 10.0) {
                                        String.format(Locale.getDefault(), "%.1f", value)
                                    } else {
                                        String.format(Locale.getDefault(), "%.0f", value)
                                    }
                                },
                            itemPlacer = itemPlacer,
                        ),
                    marker = InvisibleMarker,
                    markerVisibilityListener = markerVisibilityListener,
                    getXStep = { _, _, _ -> 0.1 },
                ),
            modelProducer = modelProducer,
            zoomState =
                rememberVicoZoomState(
                    zoomEnabled = false,
                    initialZoom = Zoom.Content,
                ),
            modifier = Modifier.fillMaxWidth().height(200.dp),
        )

        VicoChartTooltipOverlay(
            selectedPointOffset = selectedPointOffset,
            modifier = Modifier.fillMaxWidth().height(200.dp),
        )

        if (tooltipState != null) {
            DataPointTooltip(
                isVisible = true,
                data = tooltipState!!,
                onDismissRequest = { tooltipState = null },
            )
        }
    }
}

@Composable
fun ElevationChartCard(
    chartData: List<Pair<Double, Double>>,
    modifier: Modifier = Modifier,
    unitSystem: UnitSystem = UnitSystem.METRIC,
    parentScrollInProgress: () -> Boolean = { false },
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        Column(Modifier.padding(MaterialTheme.spacing.medium)) {
            Text(
                text = stringResource(R.string.workout_chart_elevation_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(MaterialTheme.spacing.medium))
            ElevationChart(
                chartData = chartData,
                unitSystem = unitSystem,
                parentScrollInProgress = parentScrollInProgress,
            )
        }
    }
}

@Composable
private fun ElevationChart(
    chartData: List<Pair<Double, Double>>,
    unitSystem: UnitSystem,
    parentScrollInProgress: () -> Boolean = { false },
) {
    var tooltipState by remember { mutableStateOf<DataPointTooltipData?>(null) }
    var selectedPointOffset by remember { mutableStateOf<Offset?>(null) }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }

    val displayData =
        remember(chartData, unitSystem) {
            chartData.map { (distKm, altM) ->
                val dist =
                    if (unitSystem == UnitSystem.IMPERIAL) {
                        distKm * UnitConverter.KM_TO_MI.toDouble()
                    } else {
                        distKm
                    }
                val alt =
                    if (unitSystem == UnitSystem.IMPERIAL) {
                        altM * UnitConverter.METERS_TO_FEET.toDouble()
                    } else {
                        altM
                    }
                (kotlin.math.round(dist * 1000.0) / 1000.0) to alt
            }
        }

    val distanceUnit =
        if (unitSystem == UnitSystem.IMPERIAL) {
            stringResource(R.string.workout_metric_distance_unit_mi)
        } else {
            stringResource(R.string.workout_metric_distance_unit_km)
        }
    val elevationUnit =
        if (unitSystem == UnitSystem.IMPERIAL) {
            stringResource(R.string.workout_metric_elevation_unit_ft)
        } else {
            stringResource(R.string.workout_metric_elevation_unit_m)
        }

    LaunchedEffect(tooltipState) {
        if (tooltipState == null) {
            selectedPointOffset = null
            selectedIndex = null
        }
    }

    LaunchedEffect(selectedIndex, displayData) {
        val idx = selectedIndex
        if (idx != null && idx in displayData.indices) {
            val point = displayData[idx]
            val distStr = String.format(Locale.getDefault(), "%.2f %s", point.first, distanceUnit)
            val valStr = String.format(Locale.getDefault(), "%.0f %s", point.second, elevationUnit)
            tooltipState =
                DataPointTooltipData(
                    valueText = valStr,
                    dateText = distStr,
                    offset =
                        selectedPointOffset?.let {
                            IntOffset(it.x.toInt(), it.y.toInt())
                        } ?: IntOffset(0, 0),
                )
        } else {
            tooltipState = null
            selectedPointOffset = null
        }
    }

    val currentParentScrollInProgress by rememberUpdatedState(parentScrollInProgress)
    LaunchedEffect(Unit) {
        snapshotFlow { currentParentScrollInProgress() }.collect { inProgress ->
            if (inProgress) {
                tooltipState = null
                selectedPointOffset = null
            }
        }
    }

    val modelProducer = remember { CartesianChartModelProducer() }

    LaunchedEffect(displayData) {
        if (displayData.isEmpty()) return@LaunchedEffect
        modelProducer.runTransaction {
            lineModel {
                series(
                    x = displayData.map { it.first },
                    y = displayData.map { it.second },
                )
            }
        }
    }

    val maxDistance = displayData.lastOrNull()?.first ?: 0.0
    val distanceLabels = remember(maxDistance) { computeDistanceLabels(maxDistance, 5) }

    val itemPlacer =
        remember(distanceLabels) {
            val base =
                HorizontalAxis.ItemPlacer.aligned(
                    spacing = { 1 },
                    addExtremeLabelPadding = true,
                )
            object : HorizontalAxis.ItemPlacer by base {
                override fun getLabelValues(
                    context: CartesianDrawingContext,
                    visibleXRange: ClosedFloatingPointRange<Double>,
                    fullXRange: ClosedFloatingPointRange<Double>,
                    maxLabelWidth: Float,
                ): List<Double> = distanceLabels.filter { it in fullXRange }
            }
        }

    val rangeProvider =
        remember(displayData, maxDistance) {
            val values = displayData.map { it.second }
            val lo = values.minOrNull() ?: 0.0
            val hi = values.maxOrNull() ?: 0.0
            CartesianLayerRangeProvider.fixed(
                minX = 0.0,
                maxX = maxDistance.coerceAtLeast(0.1),
                minY = (lo - 5.0).coerceAtLeast(0.0),
                maxY = hi + 5.0,
            )
        }

    val labelComponent = ChartDefaults.labelTextComponent()
    val axisLabelComponent = ChartDefaults.axisLabelTextComponent()
    val guidelineComponent = ChartDefaults.guidelineComponent()

    val markerVisibilityListener =
        rememberChartMarkerVisibilityListener(
            onPointSelected = { x, _, canvasX, canvasY ->
                selectedPointOffset = Offset(canvasX, canvasY)
                val idx =
                    displayData.indices.minByOrNull { abs(displayData[it].first - x) } ?: 0
                selectedIndex = idx
            },
        )

    val prevActionLabel = stringResource(CoreUiR.string.action_previous_point)
    val nextActionLabel = stringResource(CoreUiR.string.action_next_point)
    val clearActionLabel = stringResource(CoreUiR.string.action_clear_selection)

    val customActionsList =
        remember(selectedIndex, displayData) {
            val list = mutableListOf<CustomAccessibilityAction>()
            if (displayData.isNotEmpty()) {
                list.add(
                    CustomAccessibilityAction(prevActionLabel) {
                        val curr = selectedIndex ?: -1
                        selectedIndex = if (curr > 0) curr - 1 else displayData.lastIndex
                        true
                    },
                )
                list.add(
                    CustomAccessibilityAction(nextActionLabel) {
                        val curr = selectedIndex ?: -1
                        selectedIndex = if (curr != -1 && curr < displayData.lastIndex) curr + 1 else 0
                        true
                    },
                )
            }
            if (selectedIndex != null) {
                list.add(
                    CustomAccessibilityAction(clearActionLabel) {
                        selectedIndex = null
                        true
                    },
                )
            }
            list
        }

    val chartSummary = stringResource(R.string.chart_accessibility_elevation_summary)
    val selectedValueDescription =
        selectedIndex?.let { idx ->
            val point = displayData.getOrNull(idx)
            if (point != null) {
                val distStr = String.format(Locale.getDefault(), "%.2f %s", point.first, distanceUnit)
                val valStr = String.format(Locale.getDefault(), "%.0f %s", point.second, elevationUnit)
                stringResource(R.string.chart_accessibility_selected_elevation, valStr, distStr)
            } else {
                null
            }
        } ?: stringResource(CoreUiR.string.chart_accessibility_no_selection)

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("ElevationChartCanvas")
                .semantics {
                    contentDescription = chartSummary
                    stateDescription = selectedValueDescription
                    customActions = customActionsList
                }.pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var isMultiTouch = false
                        while (!isMultiTouch) {
                            val event = awaitPointerEvent()
                            if (event.changes.none { it.pressed }) break
                            if (event.changes.size > 1) {
                                isMultiTouch = true
                                tooltipState = null
                                selectedPointOffset = null
                            }
                        }
                    }
                },
    ) {
        CartesianChartHost(
            chart =
                rememberCartesianChart(
                    rememberLineCartesianLayer(
                        lineProvider =
                            LineCartesianLayer.LineProvider.series(
                                LineCartesianLayer.rememberLine(
                                    fill = LineCartesianLayer.LineFill.single(Fill(MaterialTheme.colorScheme.tertiary)),
                                    areaFill =
                                        LineCartesianLayer.AreaFill.single(
                                            Fill(
                                                brush =
                                                    Brush.verticalGradient(
                                                        colors =
                                                            listOf(
                                                                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f),
                                                                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.0f),
                                                            ),
                                                    ),
                                            ),
                                        ),
                                    interpolator = LineCartesianLayer.Interpolator.cubic(0.2f),
                                ),
                            ),
                        rangeProvider = rangeProvider,
                    ),
                    startAxis =
                        VerticalAxis.rememberStart(
                            label = labelComponent,
                            title = { elevationUnit },
                            titleComponent = axisLabelComponent,
                            guideline = guidelineComponent,
                            valueFormatter =
                                CartesianValueFormatter { _, value, _ ->
                                    String.format(Locale.getDefault(), "%.0f", value)
                                },
                        ),
                    bottomAxis =
                        HorizontalAxis.rememberBottom(
                            label = labelComponent,
                            title = { distanceUnit },
                            titleComponent = axisLabelComponent,
                            guideline = guidelineComponent,
                            valueFormatter =
                                CartesianValueFormatter { _, value, _ ->
                                    if (maxDistance < 10.0) {
                                        String.format(Locale.getDefault(), "%.1f", value)
                                    } else {
                                        String.format(Locale.getDefault(), "%.0f", value)
                                    }
                                },
                            itemPlacer = itemPlacer,
                        ),
                    marker = InvisibleMarker,
                    markerVisibilityListener = markerVisibilityListener,
                    getXStep = { _, _, _ -> 0.1 },
                ),
            modelProducer = modelProducer,
            zoomState =
                rememberVicoZoomState(
                    zoomEnabled = false,
                    initialZoom = Zoom.Content,
                ),
            modifier = Modifier.fillMaxWidth().height(200.dp),
        )

        VicoChartTooltipOverlay(
            selectedPointOffset = selectedPointOffset,
            pulseColor = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.fillMaxWidth().height(200.dp),
        )

        if (tooltipState != null) {
            DataPointTooltip(
                isVisible = true,
                data = tooltipState!!,
                onDismissRequest = { tooltipState = null },
            )
        }
    }
}

private fun computeDistanceLabels(
    maxDistance: Double,
    target: Int = 5,
): List<Double> {
    if (maxDistance <= 0.0) return listOf(0.0)
    val intervals = (target - 1).coerceAtLeast(1)
    val step = maxDistance / intervals
    return (0..intervals).map { (it * step) }.distinct()
}
