package app.readylytics.health.feature.workouts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.ui.common.ChartUtils
import app.readylytics.health.core.ui.common.DailyDataPoint
import app.readylytics.health.core.ui.components.ChartDefaults
import app.readylytics.health.core.ui.components.DataPointTooltip
import app.readylytics.health.core.ui.components.DataPointTooltipData
import app.readylytics.health.domain.display.MetricFormatter
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

@Composable
internal fun AcwrChartCard(
    trimpPoints: List<DailyDataPoint>,
    ratioPoints: List<DailyDataPoint>,
    rangeStartMs: Long,
    rangeDays: Int,
    scrollState: VicoScrollState,
    zoomState: VicoZoomState,
    modifier: Modifier = Modifier,
    parentScrollInProgress: () -> Boolean = { false },
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.acwr_training_load),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            if (trimpPoints.isEmpty() && ratioPoints.isEmpty()) {
                EmptyAcwrChartPlaceholder()
            } else {
                AcwrChart(
                    trimpPoints = trimpPoints,
                    ratioPoints = ratioPoints,
                    rangeStartMs = rangeStartMs,
                    rangeDays = rangeDays,
                    scrollState = scrollState,
                    zoomState = zoomState,
                    parentScrollInProgress = parentScrollInProgress,
                )
            }
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

    // Derive tooltipState directly from selectedState to avoid separate side-effects.
    // This eliminates extra LaunchedEffect recomposition passes and keeps the state flow simple.
    val tooltipState =
        remember(selectedState, rangeStartMs, trimpFormat, strainFormat) {
            selectedState?.let { s ->
                val date = ChartUtils.dayOffsetToLocalDate(s.dayOffset, rangeStartMs)
                val anchorY = s.lineCanvasY ?: s.barCanvasYTop ?: 0f
                val trimpText = s.trimpValue?.let { MetricFormatter.roundTrimp(it).toString() } ?: "—"
                val strainText = MetricFormatter.formatStrain(s.strainRatioValue)
                DataPointTooltipData(
                    valueText = trimpFormat.format(trimpText),
                    dateText = strainFormat.format(strainText),
                    extraLine = ChartUtils.formatTooltipDate(date),
                    offset = IntOffset(s.canvasX.toInt(), anchorY.toInt()),
                )
            }
        }

    // ── Colours & Vico style helpers ─────────────────────────────────────────
    val ratioColor = MaterialTheme.colorScheme.tertiary
    val trimpColor = MaterialTheme.colorScheme.primary
    val labelComponent = ChartDefaults.labelTextComponent()
    val axisLabelComponent = ChartDefaults.axisLabelTextComponent()
    val guidelineComponent = ChartDefaults.guidelineComponent()
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
        remember(trimpPoints, rangeDays) {
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
                ) = (rangeDays - 1).toDouble()

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
        remember(ratioPoints, rangeDays) {
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
                ) = (rangeDays - 1).toDouble()

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

    val xAxisFormatter = ChartDefaults.rememberDayOffsetFormatter(rangeStartMs)

    LaunchedEffect(trimpPoints, ratioPoints) {
        modelProducer.runTransaction {
            val validTrimp = trimpPoints.filter { it.value != null }
            if (validTrimp.isNotEmpty()) {
                columnModel {
                    series(
                        x = validTrimp.map { it.dayOffset },
                        y = validTrimp.mapNotNull { it.value?.toDouble() },
                    )
                }
            }
            val validRatio = ratioPoints.filter { it.value != null }
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

    val dotComponent = rememberShapeComponent(fill = Fill(ratioColor), shape = CircleShape)
    val ratioLine =
        LineCartesianLayer.rememberLine(
            fill = LineCartesianLayer.LineFill.single(Fill(ratioColor)),
            areaFill =
                LineCartesianLayer.AreaFill.single(
                    Fill(
                        brush =
                            Brush.verticalGradient(
                                colors = listOf(ratioColor.copy(alpha = 0.3f), ratioColor.copy(alpha = 0.0f)),
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
                        fill = Fill(trimpColor),
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
            trimpPoints = trimpPoints,
            ratioPoints = ratioPoints,
            onStateChanged = { selectedState = it },
        )

    // ── Chart host + animated overlay ────────────────────────────────────────
    val chartHeight = 220.dp
    val trimpTitle = stringResource(R.string.workout_metric_trimp)
    val strainTitle = stringResource(R.string.workout_metric_strain)
    Box(modifier = modifier.fillMaxWidth()) {
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
                                remember(
                                    rangeDays,
                                ) { ChartDefaults.itemPlacerForRangeDays(rangeDays) },
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
                if (s.lineCanvasY == null && s.strainRatioValue != null && bounds != null && ratioAxisMax > 0.0) {
                    val fraction = (1.0 - (s.strainRatioValue / ratioAxisMax)).coerceIn(0.0, 1.0)
                    s.copy(lineCanvasY = (bounds.top + fraction * bounds.height).toFloat())
                } else {
                    s
                }
            }

        AcwrChartOverlay(
            selectedState = overlayState,
            trimpColor = trimpColor,
            ratioColor = ratioColor,
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

    // ── Legends (below chart) ─────────────────────────────────────────────────
    Spacer(Modifier.height(8.dp))
    AcwrChartLegends(
        trimpColor = trimpColor,
        ratioColor = ratioColor,
    )
}

@Composable
private fun EmptyAcwrChartPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().height(180.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.message_no_data_available),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
