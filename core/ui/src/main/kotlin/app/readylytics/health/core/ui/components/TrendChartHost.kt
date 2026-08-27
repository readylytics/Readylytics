package app.readylytics.health.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.model.domain.model.BucketZoneBands
import app.readylytics.health.core.model.domain.model.ZoneBand
import app.readylytics.health.core.ui.R
import app.readylytics.health.core.ui.common.ChartUtils
import app.readylytics.health.core.ui.common.DailyDataPoint
import app.readylytics.health.core.ui.common.rememberPeriodOrdinalLabel
import com.patrykandpatrick.vico.compose.cartesian.CartesianChart
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.decoration.Decoration
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import java.util.Locale
import kotlin.math.roundToInt

private val TREND_CHART_HEIGHT_DP = 180.dp

@Composable
internal fun TrendChartHostBox(
    renderData: TrendChartRenderData,
    config: TrendChartConfig,
    bounds: TrendChartBounds,
    uiState: TrendChartUIState,
    zoneBands: List<ZoneBand>?,
    bucketZoneBands: List<BucketZoneBands>?,
    historicalBaseline: List<DailyDataPoint>?,
    modifier: Modifier = Modifier,
) {
    var tooltipState by remember { mutableStateOf<DataPointTooltipData?>(null) }
    var selectedPointOffset by remember { mutableStateOf<Offset?>(null) }

    SetupTooltipAutoDismiss(
        tooltipState = tooltipState,
        scrollState = uiState.scrollState,
        parentScrollInProgress = uiState.parentScrollInProgress,
        onClear = {
            tooltipState = null
            selectedPointOffset = null
        },
    )

    val dotColor = MaterialTheme.colorScheme.primary
    val baselineColor = MaterialTheme.colorScheme.onSurfaceVariant
    val modelProducer = rememberTrendModelProducer(renderData.validPoints, historicalBaseline)

    val visuals = TrendChartVisuals(zoneBands, bucketZoneBands, historicalBaseline, dotColor, baselineColor)
    val tooltipWrapper =
        TrendChartTooltipState(selectedPointOffset, tooltipState, { selectedPointOffset = it }, {
            tooltipState =
                it
        })
    val chartContext =
        TrendChartContext(renderData, config, bounds, visuals, uiState.parentScrollInProgress, tooltipWrapper)
    val chart = rememberTrendCartesianChart(chartContext)

    Box(modifier = modifier.fillMaxWidth()) {
        CartesianChartHost(
            chart = chart,
            modelProducer = modelProducer,
            scrollState = uiState.scrollState,
            zoomState = uiState.zoomState,
            modifier = Modifier.fillMaxWidth().height(TREND_CHART_HEIGHT_DP),
            chartAreaHeight = TREND_CHART_HEIGHT_DP,
        )

        VicoChartTooltipOverlay(
            selectedPointOffset = selectedPointOffset,
            pulseColor = dotColor,
            modifier = Modifier.fillMaxWidth().height(TREND_CHART_HEIGHT_DP),
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
private fun SetupTooltipAutoDismiss(
    tooltipState: DataPointTooltipData?,
    scrollState: com.patrykandpatrick.vico.compose.cartesian.VicoScrollState,
    parentScrollInProgress: () -> Boolean,
    onClear: () -> Unit,
) {
    LaunchedEffect(tooltipState) {
        if (tooltipState == null) onClear()
    }
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.value }.collect { onClear() }
    }
    val currentParentScrollInProgress by rememberUpdatedState(parentScrollInProgress)
    LaunchedEffect(Unit) {
        snapshotFlow { currentParentScrollInProgress() }.collect { onClear() }
    }
}

@Composable
private fun rememberTrendModelProducer(
    validPoints: List<DailyDataPoint>,
    historicalBaseline: List<DailyDataPoint>?,
): CartesianChartModelProducer {
    val modelProducer = remember { CartesianChartModelProducer() }
    LaunchedEffect(validPoints, historicalBaseline) {
        modelProducer.runTransaction {
            lineModel {
                series(
                    x = validPoints.map(DailyDataPoint::dayOffset),
                    y = validPoints.map { requireNotNull(it.value).toDouble() },
                )
                if (!historicalBaseline.isNullOrEmpty()) {
                    series(
                        x = historicalBaseline.map(DailyDataPoint::dayOffset),
                        y = historicalBaseline.map { requireNotNull(it.value).toDouble() },
                    )
                }
            }
        }
    }
    return modelProducer
}

@Composable
private fun rememberTrendCartesianChart(context: TrendChartContext): CartesianChart {
    val decorations = rememberChartDecorations(context)
    val lineProvider =
        rememberTrendLineProvider(
            context.visuals.dotColor,
            context.visuals.baselineColor,
            context.visuals.historicalBaseline,
        )
    val rangeProvider = rememberChartRangeProvider(context)
    val markerListener = rememberTrendMarkerListener(context)
    val startAxis = rememberStartVerticalAxis(context)
    val bottomAxis = rememberBottomHorizontalAxis(context)

    return rememberCartesianChart(
        rememberLineCartesianLayer(lineProvider = lineProvider, rangeProvider = rangeProvider),
        startAxis = startAxis,
        bottomAxis = bottomAxis,
        decorations = decorations,
        marker = InvisibleMarker,
        markerVisibilityListener = markerListener,
    )
}

@Composable
private fun rememberChartDecorations(context: TrendChartContext): List<Decoration> {
    val zoneBandDeco =
        rememberZoneBandDecoration(
            context.visuals.zoneBands,
            context.visuals.bucketZoneBands,
            context.bounds.minY,
            context.bounds.maxY,
            context.config.rangeDays,
        )
    return rememberTrendChartDecorations(
        zoneBandDecoration = zoneBandDeco,
        shouldShowBaseline = context.bounds.shouldShowBaseline,
        baselineValue = context.bounds.baselineValue,
        baselineColor = context.visuals.baselineColor,
        hasHistoricalBaseline = context.bounds.hasHistoricalBaseline,
        bucketZoneBands = context.visuals.bucketZoneBands,
    )
}

@Composable
private fun rememberChartRangeProvider(context: TrendChartContext): CartesianLayerRangeProvider =
    remember(context.bounds.minY, context.bounds.maxY, context.config.rangeDays) {
        CartesianLayerRangeProvider.fixed(
            minX = 0.0,
            maxX = (context.config.rangeDays - 1).toDouble(),
            minY = context.bounds.minY,
            maxY = context.bounds.maxY,
        )
    }

@Composable
private fun rememberStartVerticalAxis(context: TrendChartContext) =
    VerticalAxis.rememberStart(
        label = ChartDefaults.labelTextComponent(),
        valueFormatter = rememberStartAxisFormatter(context.config.axisDecimalPlaces),
        guideline = ChartDefaults.guidelineComponent(),
        title = rememberAxisTitle(context.config.baselineUnit),
        titleComponent = ChartDefaults.axisLabelTextComponent(),
    )

@Composable
private fun rememberBottomHorizontalAxis(context: TrendChartContext) =
    HorizontalAxis.rememberBottom(
        label = ChartDefaults.labelTextComponent(),
        valueFormatter = ChartDefaults.rememberPeriodFormatter(context.config.rangeStartMs, context.config.granularity),
        itemPlacer =
            ChartDefaults.rememberTrendAxisItemPlacer(
                rangeDays = context.config.rangeDays,
                granularity = context.config.granularity,
                rangeStartMs = context.config.rangeStartMs,
            ),
        guideline = ChartDefaults.guidelineComponent(),
    )

@Composable
private fun rememberTrendMarkerListener(
    context: TrendChartContext,
): com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarkerVisibilityListener {
    val ordinalLabel = rememberPeriodOrdinalLabel(context.config.granularity)
    val weekRangeTemplate = stringResource(R.string.tooltip_week_range)
    val currentParentScroll by rememberUpdatedState(context.parentScrollInProgress)

    return rememberChartMarkerVisibilityListener { x, _, canvasX, canvasY ->
        if (!shouldProcessTrendMarker(currentParentScroll())) return@rememberChartMarkerVisibilityListener
        val dayOffset = x.toInt()
        val nearest = context.renderData.pointByDayOffset[dayOffset]
        val date = ChartUtils.dayOffsetToLocalDate(dayOffset, context.config.rangeStartMs)
        val valueText =
            formatTrendTooltipValue(
                value = nearest?.value,
                decimalPlaces = context.config.tooltipDecimalPlaces,
                hideUnit = context.config.hideUnitInTooltip,
                unit = context.config.baselineUnit,
            )
        val nextOffset = Offset(canvasX, canvasY)
        val nextTooltip =
            DataPointTooltipData(
                valueText = valueText,
                dateText = formatTrendTooltipDate(context.config.granularity, date, ordinalLabel, weekRangeTemplate),
                offset = IntOffset(canvasX.toInt(), canvasY.toInt()),
            )
        if (shouldAssignTrendMarkerState(
                context.tooltip.selectedPointOffset,
                nextOffset,
            )
        ) {
            context.tooltip.onUpdateOffset(nextOffset)
        }
        if (shouldAssignTrendMarkerState(
                context.tooltip.tooltipState,
                nextTooltip,
            )
        ) {
            context.tooltip.onUpdateTooltip(nextTooltip)
        }
    }
}

@Composable
private fun rememberStartAxisFormatter(axisDecimalPlaces: Int): CartesianValueFormatter =
    remember(axisDecimalPlaces) {
        CartesianValueFormatter { _, value, _ ->
            if (axisDecimalPlaces == 0) {
                value.roundToInt().toString()
            } else {
                String.format(Locale.getDefault(), "%.${axisDecimalPlaces}f", value)
            }
        }
    }
