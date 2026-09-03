package app.readylytics.health.feature.workouts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.model.domain.display.MetricFormatter
import app.readylytics.health.core.ui.common.ChartUtils
import app.readylytics.health.core.ui.common.DailyDataPoint
import app.readylytics.health.core.ui.common.TrendGranularity
import app.readylytics.health.core.ui.common.periodLabelFor
import app.readylytics.health.core.ui.common.rememberPeriodOrdinalLabel
import app.readylytics.health.core.ui.components.ChartDefaults
import app.readylytics.health.core.ui.components.DataPointTooltip
import app.readylytics.health.core.ui.components.DataPointTooltipData
import app.readylytics.health.feature.workouts.R
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.VicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.VicoZoomState
import com.patrykandpatrick.vico.compose.cartesian.axis.Axis
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.columnModel
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.data.ExtraStore
import kotlin.math.ceil
import app.readylytics.health.core.ui.R as CoreUiR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrainingLoadMetricToggle(
    selectedMetric: TrainingLoadMetric,
    onMetricSelected: (TrainingLoadMetric) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        TrainingLoadMetric.entries.forEachIndexed { index, metric ->
            SegmentedButton(
                selected = selectedMetric == metric,
                onClick = { onMetricSelected(metric) },
                shape =
                    SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = TrainingLoadMetric.entries.size,
                    ),
                label = {
                    Text(
                        text =
                            stringResource(
                                if (metric == TrainingLoadMetric.ACWR) {
                                    R.string.training_load_metric_acwr
                                } else {
                                    R.string.training_load_metric_tsb
                                },
                            ),
                    )
                },
            )
        }
    }
}

@Composable
internal fun AcwrChartCard(
    chartData: AcwrChartData,
    onMetricSelected: (TrainingLoadMetric) -> Unit,
    scrollState: VicoScrollState,
    zoomState: VicoZoomState,
    modifier: Modifier = Modifier,
    parentScrollInProgress: () -> Boolean = { false },
) {
    val trimpColor = MaterialTheme.colorScheme.primary
    val ratioColor = MaterialTheme.colorScheme.tertiary
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.padding(MaterialTheme.spacing.medium)) {
            Text(
                text = stringResource(R.string.acwr_training_load),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(MaterialTheme.spacing.small))
            TrainingLoadMetricToggle(
                selectedMetric = chartData.selectedMetric,
                onMetricSelected = onMetricSelected,
            )
            Spacer(Modifier.height(MaterialTheme.spacing.medium))
            AcwrChartContent(
                chartData = chartData,
                scrollState = scrollState,
                zoomState = zoomState,
                parentScrollInProgress = parentScrollInProgress,
                trimpColor = trimpColor,
                ratioColor = ratioColor,
            )
        }
    }
}

@Composable
private fun AcwrChartContent(
    chartData: AcwrChartData,
    scrollState: VicoScrollState,
    zoomState: VicoZoomState,
    parentScrollInProgress: () -> Boolean,
    trimpColor: Color,
    ratioColor: Color,
) {
    when (chartData.selectedMetric) {
        TrainingLoadMetric.ACWR -> {
            if (chartData.trimpPoints.isEmpty() && chartData.ratioPoints.isEmpty()) {
                EmptyAcwrChartPlaceholder()
            } else {
                AcwrChart(
                    trimpPoints = chartData.trimpPoints,
                    ratioPoints = chartData.ratioPoints,
                    rangeStartMs = chartData.rangeStartMs,
                    rangeDays = chartData.rangeDays,
                    scrollState = scrollState,
                    zoomState = zoomState,
                    parentScrollInProgress = parentScrollInProgress,
                    granularity = chartData.granularity,
                )
            }
            Spacer(Modifier.height(MaterialTheme.spacing.small))
            AcwrChartLegends(
                trimpColor = trimpColor,
                ratioColor = ratioColor,
            )
        }
        TrainingLoadMetric.TSB -> {
            if (chartData.tsbPoints.none { it.value != null }) {
                EmptyAcwrChartPlaceholder()
            } else {
                TsbChart(
                    tsbPoints = chartData.tsbPoints,
                    rangeStartMs = chartData.rangeStartMs,
                    rangeDays = chartData.rangeDays,
                    scrollState = scrollState,
                    zoomState = zoomState,
                    granularity = chartData.granularity,
                )
            }
            Spacer(Modifier.height(MaterialTheme.spacing.small))
            TsbChartLegend()
        }
    }
}

@Composable
private fun AcwrChart(
    trimpPoints: List<DailyDataPoint>,
    ratioPoints: List<DailyDataPoint>,
    rangeStartMs: Long,
    rangeDays: Int,
    scrollState: VicoScrollState,
    zoomState: VicoZoomState,
    modifier: Modifier = Modifier,
    parentScrollInProgress: () -> Boolean = { false },
    granularity: TrendGranularity = TrendGranularity.DAILY,
) {
    // Selection state is keyed on the data inputs so it clears automatically when the
    // chart range or underlying data changes, preventing stale coordinates and values.
    var selectedState by remember(trimpPoints, ratioPoints, rangeStartMs) { mutableStateOf<AcwrSelectedState?>(null) }

    // Dismiss the tooltip/selection when the chart is panned or the parent list scrolls
    // vertically, so the popup never detaches from its anchor point.
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.value }.collect {
            selectedState = null
        }
    }
    val currentParentScrollInProgress by rememberUpdatedState(parentScrollInProgress)
    LaunchedEffect(Unit) {
        snapshotFlow { currentParentScrollInProgress() }.collect { inProgress ->
            if (inProgress) selectedState = null
        }
    }

    var layerBounds by remember { mutableStateOf<Rect?>(null) }
    val invisibleMarker =
        remember {
            object : com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarker {
                override fun drawUnderLayers(
                    context: com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext,
                    targets: List<com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarker.Target>,
                ) {
                    layerBounds = context.layerBounds
                }

                override fun drawOverLayers(
                    context: com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext,
                    targets: List<com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarker.Target>,
                ) {
                    layerBounds = context.layerBounds
                }
            }
        }

    // Read string resources outside remember so they can be used as keys and accessed
    // inside the lambda (where Composable calls are not permitted).
    val trimpFormat = stringResource(R.string.acwr_tooltip_trimp_format)
    val strainFormat = stringResource(R.string.acwr_tooltip_strain_format)
    val avgTrimpFormat = stringResource(R.string.acwr_tooltip_avg_trimp_format)
    val avgStrainFormat = stringResource(R.string.acwr_tooltip_avg_strain_format)

    val ordinalLabel = rememberPeriodOrdinalLabel(granularity)
    val periodLabels =
        remember(trimpPoints, rangeStartMs, granularity, ordinalLabel) {
            if (granularity == TrendGranularity.DAILY) {
                emptyList()
            } else {
                trimpPoints.map { point ->
                    val date = ChartUtils.dayOffsetToLocalDate(point.dayOffset, rangeStartMs)
                    periodLabelFor(granularity, date, ordinalLabel)
                }
            }
        }

    // Derive tooltipState directly from selectedState to avoid separate side-effects.
    // This eliminates extra LaunchedEffect recomposition passes and keeps the state flow simple.
    val tooltipState =
        remember(
            selectedState,
            rangeStartMs,
            trimpFormat,
            strainFormat,
            avgTrimpFormat,
            avgStrainFormat,
            granularity,
            periodLabels,
        ) {
            selectedState?.let { s ->
                val anchorY = s.lineCanvasY ?: s.barCanvasYTop ?: 0f
                val trimpText = s.trimpValue?.let { MetricFormatter.roundTrimp(it).toString() } ?: "—"
                val strainText = MetricFormatter.formatStrain(s.strainRatioValue)

                if (granularity != TrendGranularity.DAILY) {
                    val periodLabel = periodLabels.getOrElse(s.dayOffset) { "" }
                    DataPointTooltipData(
                        valueText = periodLabel,
                        dateText = avgTrimpFormat.format(trimpText),
                        extraLine = avgStrainFormat.format(strainText),
                        offset = IntOffset(s.canvasX.toInt(), anchorY.toInt()),
                    )
                } else {
                    val date = ChartUtils.dayOffsetToLocalDate(s.dayOffset, rangeStartMs)
                    DataPointTooltipData(
                        valueText = trimpFormat.format(trimpText),
                        dateText = strainFormat.format(strainText),
                        extraLine = ChartUtils.formatTooltipDate(date),
                        offset = IntOffset(s.canvasX.toInt(), anchorY.toInt()),
                    )
                }
            }
        }

    // ── Colours & Vico style helpers ─────────────────────────────────────────
    val labelComponent = ChartDefaults.labelTextComponent()
    val axisLabelComponent = ChartDefaults.axisLabelTextComponent()
    val guidelineComponent = ChartDefaults.guidelineComponent()

    val remappedTrimpPoints =
        remember(trimpPoints, granularity) {
            if (granularity == TrendGranularity.DAILY) {
                trimpPoints
            } else {
                trimpPoints.mapIndexed { i, p -> p.copy(dayOffset = i) }
            }
        }
    val remappedRatioPoints =
        remember(ratioPoints, granularity) {
            if (granularity == TrendGranularity.DAILY) {
                ratioPoints
            } else {
                ratioPoints.mapIndexed { i, p -> p.copy(dayOffset = i) }
            }
        }
    val xAxisRangeDays =
        remember(trimpPoints, granularity) {
            if (granularity == TrendGranularity.DAILY) {
                rangeDays
            } else {
                trimpPoints.size
            }
        }

    val trimpAxisFormatter =
        remember {
            CartesianValueFormatter {
                _,
                value,
                _,
                ->
                MetricFormatter.roundTrimp(value.toFloat()).toString()
            }
        }
    val ratioAxisFormatter =
        remember {
            CartesianValueFormatter {
                _,
                value,
                _,
                ->
                MetricFormatter.formatStrain(value.toFloat())
            }
        }

    val modelProducer = remember { CartesianChartModelProducer() }

    val trimpRangeProvider =
        remember(remappedTrimpPoints, xAxisRangeDays) {
            object : CartesianLayerRangeProvider {
                override fun getMinX(
                    minX: Double,
                    maxX: Double,
                    extraStore: ExtraStore,
                ) = 0.0

                override fun getMaxX(
                    minX: Double,
                    maxX: Double,
                    extraStore: ExtraStore,
                ) = (xAxisRangeDays - 1).toDouble()

                override fun getMaxY(
                    minY: Double,
                    maxY: Double,
                    extraStore: ExtraStore,
                ): Double = (ceil(maxY / 25.0) * 25.0).coerceAtLeast(100.0)

                override fun getMinY(
                    minY: Double,
                    maxY: Double,
                    extraStore: ExtraStore,
                ): Double = 0.0
            }
        }

    val ratioRangeProvider =
        remember(remappedRatioPoints, xAxisRangeDays) {
            object : CartesianLayerRangeProvider {
                override fun getMinX(
                    minX: Double,
                    maxX: Double,
                    extraStore: ExtraStore,
                ) = 0.0

                override fun getMaxX(
                    minX: Double,
                    maxX: Double,
                    extraStore: ExtraStore,
                ) = (xAxisRangeDays - 1).toDouble()

                override fun getMaxY(
                    minY: Double,
                    maxY: Double,
                    extraStore: ExtraStore,
                ): Double = (ceil(maxY / 0.5) * 0.5).coerceAtLeast(2.0)

                override fun getMinY(
                    minY: Double,
                    maxY: Double,
                    extraStore: ExtraStore,
                ): Double = 0.0
            }
        }

    // Upper bound of the Strain Ratio (end) axis — must match ratioRangeProvider.getMaxY so we
    // can map a strain value back to a canvas-y when Vico's touch only reported the TRIMP column.
    val ratioAxisMax =
        remember(ratioPoints) {
            val dataMax = ratioPoints.mapNotNull { it.value?.toDouble() }.maxOrNull() ?: 0.0
            (ceil(dataMax / 0.5) * 0.5).coerceAtLeast(2.0)
        }

    val xAxisFormatter =
        if (granularity == TrendGranularity.DAILY) {
            ChartDefaults.rememberPeriodFormatter(rangeStartMs, granularity)
        } else {
            remember(periodLabels) {
                val fallback = periodLabels.firstOrNull().orEmpty()
                CartesianValueFormatter { _, value, _ ->
                    periodLabels.getOrElse(value.toInt()) { fallback }
                }
            }
        }

    LaunchedEffect(remappedTrimpPoints, remappedRatioPoints) {
        modelProducer.runTransaction {
            val validTrimp = remappedTrimpPoints.filter { it.value != null }
            if (validTrimp.isNotEmpty()) {
                columnModel {
                    series(
                        x = validTrimp.map { it.dayOffset },
                        y = validTrimp.mapNotNull { it.value?.toDouble() },
                    )
                }
            }
            val validRatio = remappedRatioPoints.filter { it.value != null }
            if (validRatio.isNotEmpty()) {
                lineModel {
                    series(
                        x = validRatio.map { it.dayOffset },
                        y = validRatio.mapNotNull { it.value?.toDouble() },
                    )
                }
            }
        }
    }

    val dotComponent = rememberShapeComponent(fill = Fill(MaterialTheme.colorScheme.tertiary), shape = CircleShape)
    val ratioLine =
        LineCartesianLayer.rememberLine(
            fill = LineCartesianLayer.LineFill.single(Fill(MaterialTheme.colorScheme.tertiary)),
            areaFill =
                LineCartesianLayer.AreaFill.single(
                    Fill(
                        brush =
                            Brush.verticalGradient(
                                colors =
                                    listOf(
                                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f),
                                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.0f),
                                    ),
                            ),
                    ),
                ),
            pointProvider =
                LineCartesianLayer.PointProvider.single(
                    LineCartesianLayer.Point(dotComponent, 6.dp),
                ),
            interpolator = LineCartesianLayer.Interpolator.cubic(0.2f),
        )

    val trimpColumn =
        rememberColumnCartesianLayer(
            columnProvider =
                ColumnCartesianLayer.ColumnProvider.series(
                    rememberLineComponent(
                        fill = Fill(MaterialTheme.colorScheme.primary),
                        thickness = 8.dp,
                        shape = CircleShape,
                    ),
                ),
            rangeProvider = trimpRangeProvider,
            verticalAxisPosition = Axis.Position.Vertical.Start,
        )

    val trimpAxisItemPlacer = remember { VerticalAxis.ItemPlacer.count(count = { 5 }) }
    val ratioAxisItemPlacer = remember { VerticalAxis.ItemPlacer.count(count = { 5 }) }

    // ── Marker listener bridges Vico touch → Compose state ───────────────────
    val markerVisibilityListener =
        rememberAcwrMarkerVisibilityListener(
            trimpPoints = remappedTrimpPoints,
            ratioPoints = remappedRatioPoints,
            onStateChanged = { selectedState = it },
        )

    // ── Chart host + animated overlay ────────────────────────────────────────
    val chartHeight = 220.dp
    val trimpTitle = stringResource(R.string.workout_metric_trimp)
    val strainTitle = stringResource(R.string.workout_metric_strain)
    val hasData =
        remember(remappedTrimpPoints, remappedRatioPoints) {
            remappedTrimpPoints.any { it.value != null } || remappedRatioPoints.any { it.value != null }
        }
    val chartModifier =
        if (hasData) {
            modifier.testTag("AcwrChart")
        } else {
            modifier
        }
    Box(modifier = chartModifier.fillMaxWidth()) {
        CartesianChartHost(
            chart =
                com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart(
                    trimpColumn,
                    rememberLineCartesianLayer(
                        lineProvider = LineCartesianLayer.LineProvider.series(ratioLine),
                        rangeProvider = ratioRangeProvider,
                        verticalAxisPosition = Axis.Position.Vertical.End,
                    ),
                    startAxis =
                        VerticalAxis.rememberStart(
                            label = labelComponent,
                            valueFormatter = trimpAxisFormatter,
                            titleComponent = axisLabelComponent,
                            title = { trimpTitle },
                            itemPlacer = trimpAxisItemPlacer,
                            guideline = guidelineComponent,
                        ),
                    endAxis =
                        VerticalAxis.rememberEnd(
                            label = labelComponent,
                            valueFormatter = ratioAxisFormatter,
                            titleComponent = axisLabelComponent,
                            title = { strainTitle },
                            itemPlacer = ratioAxisItemPlacer,
                            guideline = null,
                        ),
                    bottomAxis =
                        HorizontalAxis.rememberBottom(
                            label = labelComponent,
                            valueFormatter = xAxisFormatter,
                            itemPlacer =
                                ChartDefaults.rememberTrendAxisItemPlacer(
                                    rangeDays = xAxisRangeDays,
                                    granularity = granularity,
                                    rangeStartMs = rangeStartMs,
                                    explicitPointOffsets =
                                        if (granularity == TrendGranularity.DAILY) {
                                            emptyList()
                                        } else {
                                            remappedTrimpPoints
                                                .map { it.dayOffset }
                                                .distinct()
                                                .sorted()
                                        },
                                ),
                            guideline = guidelineComponent,
                        ),
                    marker = invisibleMarker,
                    markerVisibilityListener = markerVisibilityListener,
                ),
            modelProducer = modelProducer,
            scrollState = scrollState,
            zoomState = zoomState,
            modifier = Modifier.fillMaxWidth().height(chartHeight),
        )

        // Vico's touch resolution can report only the TRIMP column target when the tap lands on a
        // bar, leaving lineCanvasY null so the Strain Ratio pulse would not render. Recompute the
        // dot's canvas-y from the strain value + layer bounds so the ACWR point is always anchored.
        val overlayState =
            selectedState?.let { s ->
                val bounds = layerBounds
                val needsPlacement =
                    s.lineCanvasY == null && s.strainRatioValue != null && ratioAxisMax > 0.0
                if (needsPlacement && bounds != null) {
                    val fraction = (1.0 - (s.strainRatioValue / ratioAxisMax)).coerceIn(0.0, 1.0)
                    s.copy(lineCanvasY = (bounds.top + fraction * bounds.height).toFloat())
                } else {
                    s
                }
            }

        AcwrChartOverlay(
            selectedState = overlayState,
            trimpColor = MaterialTheme.colorScheme.primary,
            ratioColor = MaterialTheme.colorScheme.tertiary,
            layerBounds = layerBounds,
            chartHeight = chartHeight,
            modifier = Modifier.fillMaxWidth(),
        )

        // ── Tooltip popup ─────────────────────────────────────────────────────────
        tooltipState?.let { data ->
            DataPointTooltip(
                isVisible = true,
                data = data,
                onDismissRequest = { selectedState = null },
            )
        }
    }
}

@Composable
private fun EmptyAcwrChartPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().height(180.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(CoreUiR.string.message_no_data_available),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
