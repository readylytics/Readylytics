package app.readylytics.health.feature.workouts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.model.domain.display.MetricFormatter
import app.readylytics.health.core.ui.common.DailyDataPoint
import app.readylytics.health.core.ui.common.TrendGranularity
import app.readylytics.health.core.ui.components.AutoDismissChartSelection
import app.readylytics.health.core.ui.components.ChartDefaults
import app.readylytics.health.core.ui.components.DataPointTooltip
import app.readylytics.health.core.ui.components.rememberBoundsTrackingMarker
import app.readylytics.health.core.ui.components.rememberCountItemPlacer
import app.readylytics.health.core.ui.components.rememberCubicLineCartesianLayer
import app.readylytics.health.core.ui.components.rememberLongPressMarkerController
import app.readylytics.health.core.ui.components.rememberRoundedColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.VicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.VicoZoomState
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart

@Composable
internal fun AcwrChartCard(
    chartData: AcwrChartData,
    onMetricSelected: (TrainingLoadMetric) -> Unit,
    scrollState: VicoScrollState,
    zoomState: VicoZoomState,
    modifier: Modifier = Modifier,
    parentScrollInProgress: () -> Boolean = { false },
) {
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
                trimpColor = MaterialTheme.colorScheme.primary,
                ratioColor = MaterialTheme.colorScheme.tertiary,
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
            AcwrChartLegends(trimpColor = trimpColor, ratioColor = ratioColor)
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
                    parentScrollInProgress = parentScrollInProgress,
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
    var selectedState by remember(trimpPoints, ratioPoints, rangeStartMs) { mutableStateOf<AcwrSelectedState?>(null) }
    AutoDismissChartSelection(scrollState, parentScrollInProgress) { selectedState = null }

    var layerBounds by remember { mutableStateOf<Rect?>(null) }
    val invisibleMarker = rememberBoundsTrackingMarker { layerBounds = it }

    val periodLabels = rememberAcwrPeriodLabels(trimpPoints, rangeStartMs, granularity)
    val tooltipState = rememberAcwrTooltipData(selectedState, rangeStartMs, granularity, periodLabels)

    val remappedTrimpPoints = rememberAcwrRemappedPoints(trimpPoints, granularity)
    val remappedRatioPoints = rememberAcwrRemappedPoints(ratioPoints, granularity)
    val xAxisRangeDays =
        remember(trimpPoints, granularity) {
            if (granularity == TrendGranularity.DAILY) rangeDays else trimpPoints.size
        }

    val trimpAxisFormatter =
        remember {
            CartesianValueFormatter { _, value, _ -> MetricFormatter.roundTrimp(value.toFloat()).toString() }
        }
    val ratioAxisFormatter =
        remember {
            CartesianValueFormatter { _, value, _ -> MetricFormatter.formatStrain(value.toFloat()) }
        }

    val modelProducer = rememberAcwrModelProducer(remappedTrimpPoints, remappedRatioPoints)
    val rangeProviders = rememberAcwrRangeProviders(remappedTrimpPoints, remappedRatioPoints, xAxisRangeDays)
    val xAxisFormatter = rememberAcwrXAxisFormatter(periodLabels, rangeStartMs, granularity)

    val ratioLine =
        rememberCubicLineCartesianLayer(
            lineColor = MaterialTheme.colorScheme.tertiary,
            rangeProvider = rangeProviders.ratioRangeProvider,
        )
    val trimpColumn =
        rememberRoundedColumnCartesianLayer(
            columnColor = MaterialTheme.colorScheme.primary,
            rangeProvider = rangeProviders.trimpRangeProvider,
        )

    val markerVisibilityListener =
        rememberAcwrMarkerVisibilityListener(
            trimpPoints = remappedTrimpPoints,
            ratioPoints = remappedRatioPoints,
            onStateChanged = { selectedState = it },
        )
    val markerController = rememberLongPressMarkerController()

    val chartHeight = 220.dp
    val trimpTitle = stringResource(R.string.workout_metric_trimp)
    val strainTitle = stringResource(R.string.workout_metric_strain)
    val hasData =
        remember(remappedTrimpPoints, remappedRatioPoints) {
            remappedTrimpPoints.any { it.value != null } || remappedRatioPoints.any { it.value != null }
        }
    val chartModifier = if (hasData) modifier.testTag("AcwrChart") else modifier

    val explicitOffsets =
        remember(remappedTrimpPoints, granularity) {
            val isDaily = granularity == TrendGranularity.DAILY
            if (isDaily) emptyList() else remappedTrimpPoints.map { it.dayOffset }.distinct().sorted()
        }
    val bottomItemPlacer =
        ChartDefaults.rememberTrendAxisItemPlacer(
            rangeDays = xAxisRangeDays,
            granularity = granularity,
            rangeStartMs = rangeStartMs,
            explicitPointOffsets = explicitOffsets,
        )

    Box(modifier = chartModifier.fillMaxWidth()) {
        CartesianChartHost(
            chart =
                rememberCartesianChart(
                    trimpColumn,
                    ratioLine,
                    startAxis =
                        VerticalAxis.rememberStart(
                            label = ChartDefaults.labelTextComponent(),
                            valueFormatter = trimpAxisFormatter,
                            titleComponent = ChartDefaults.axisLabelTextComponent(),
                            title = { trimpTitle },
                            itemPlacer = rememberCountItemPlacer(5),
                            guideline = ChartDefaults.guidelineComponent(),
                        ),
                    endAxis =
                        VerticalAxis.rememberEnd(
                            label = ChartDefaults.labelTextComponent(),
                            valueFormatter = ratioAxisFormatter,
                            titleComponent = ChartDefaults.axisLabelTextComponent(),
                            title = { strainTitle },
                            itemPlacer = rememberCountItemPlacer(5),
                            guideline = null,
                        ),
                    bottomAxis =
                        HorizontalAxis.rememberBottom(
                            label = ChartDefaults.labelTextComponent(),
                            valueFormatter = xAxisFormatter,
                            itemPlacer = bottomItemPlacer,
                            guideline = ChartDefaults.guidelineComponent(),
                        ),
                    marker = invisibleMarker,
                    markerVisibilityListener = markerVisibilityListener,
                    markerController = markerController,
                ),
            modelProducer = modelProducer,
            scrollState = scrollState,
            zoomState = zoomState,
            modifier = Modifier.fillMaxWidth().height(chartHeight).then(markerController.scrubGate),
        )

        AcwrChartOverlay(
            selectedState = selectedState?.withComputedLineCanvasY(rangeProviders.ratioAxisMax, layerBounds),
            trimpColor = MaterialTheme.colorScheme.primary,
            ratioColor = MaterialTheme.colorScheme.tertiary,
            layerBounds = layerBounds,
            chartHeight = chartHeight,
            modifier = Modifier.fillMaxWidth(),
        )

        tooltipState?.let { data ->
            DataPointTooltip(
                isVisible = true,
                data = data,
                onDismissRequest = { selectedState = null },
            )
        }
    }
}
