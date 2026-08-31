package app.readylytics.health.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.model.domain.model.BucketZoneBands
import app.readylytics.health.core.model.domain.model.ZoneBand
import app.readylytics.health.core.ui.R
import app.readylytics.health.core.ui.common.DailyDataPoint
import app.readylytics.health.core.ui.common.DeltaDirection
import app.readylytics.health.core.ui.common.PeriodAverageSummary
import app.readylytics.health.core.ui.common.TrendGranularity
import com.patrykandpatrick.vico.compose.cartesian.VicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.VicoZoomState
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState

private data class TrendChartComposableState(
    val renderData: TrendChartRenderData,
    val config: TrendChartConfig,
    val bounds: TrendChartBounds,
    val uiState: TrendChartUIState,
)

private data class TrendChartParams(
    val renderData: TrendChartRenderData,
    val rangeStartMs: Long,
    val rangeDays: Int,
    val baselineUnit: String,
    val baseline: Float?,
    val baselineLabel: String?,
    val baselineUnavailableLabel: String?,
    val baselineDecimalPlaces: Int,
    val axisDecimalPlaces: Int,
    val tooltipDecimalPlaces: Int,
    val showBaseline: Boolean,
    val hideUnitInTooltip: Boolean,
    val scrollState: VicoScrollState,
    val zoomState: VicoZoomState,
    val parentScrollInProgress: () -> Boolean,
    val granularity: TrendGranularity,
    val periodSummary: PeriodAverageSummary?,
    val deltaDirection: DeltaDirection,
    val historicalBaseline: List<DailyDataPoint>?,
    val minYOverride: Double?,
    val maxYOverride: Double?,
    val modifier: Modifier,
)

@Composable
private fun buildTrendChartComposableState(params: TrendChartParams): TrendChartComposableState {
    val (minY, maxY) = calculateMinMaxY(params.renderData, params.minYOverride, params.maxYOverride)
    val baselineValue = params.baseline ?: requireNotNull(params.renderData.calculatedBaseline)
    val hasHistoricalBaseline = !params.historicalBaseline.isNullOrEmpty()
    val shouldShowBaseline =
        remember(baselineValue, minY, maxY, params.showBaseline) {
            params.showBaseline && baselineValue.toDouble() >= minY && baselineValue.toDouble() <= maxY
        }

    val config =
        TrendChartConfig(
            rangeStartMs = params.rangeStartMs,
            rangeDays = params.rangeDays,
            baselineUnit = params.baselineUnit,
            baselineDecimalPlaces = params.baselineDecimalPlaces,
            axisDecimalPlaces = params.axisDecimalPlaces,
            tooltipDecimalPlaces = params.tooltipDecimalPlaces,
            hideUnitInTooltip = params.hideUnitInTooltip,
            granularity = params.granularity,
        )
    val bounds =
        TrendChartBounds(
            minY = minY,
            maxY = maxY,
            baselineValue = baselineValue,
            shouldShowBaseline = shouldShowBaseline,
            resolvedBaselineLabel = params.baselineLabel ?: stringResource(R.string.label_baseline),
            baselineUnavailableLabel = params.baselineUnavailableLabel,
            hasHistoricalBaseline = hasHistoricalBaseline,
        )
    val uiState =
        TrendChartUIState(
            scrollState = params.scrollState,
            zoomState = params.zoomState,
            parentScrollInProgress = params.parentScrollInProgress,
            periodSummary = params.periodSummary,
            deltaDirection = params.deltaDirection,
            modifier = params.modifier,
        )

    return TrendChartComposableState(
        renderData = params.renderData,
        config = config,
        bounds = bounds,
        uiState = uiState,
    )
}

@Composable
fun TrendChart(
    points: List<DailyDataPoint>,
    rangeStartMs: Long,
    rangeDays: Int,
    baselineUnit: String,
    modifier: Modifier = Modifier,
    baseline: Float? = null,
    baselineLabel: String? = null,
    baselineUnavailableLabel: String? = null,
    baselineDecimalPlaces: Int = 0,
    axisDecimalPlaces: Int = 0,
    tooltipDecimalPlaces: Int = axisDecimalPlaces,
    showBaseline: Boolean = true,
    hideUnitInTooltip: Boolean = false,
    scrollState: VicoScrollState = rememberVicoScrollState(scrollEnabled = rangeDays > 7),
    zoomState: VicoZoomState = defaultZoomState(rangeDays),
    zoneBands: List<ZoneBand>? = null,
    bucketZoneBands: List<BucketZoneBands>? = null,
    minYOverride: Double? = null,
    maxYOverride: Double? = null,
    parentScrollInProgress: () -> Boolean = { false },
    granularity: TrendGranularity = TrendGranularity.DAILY,
    periodSummary: PeriodAverageSummary? = null,
    deltaDirection: DeltaDirection = DeltaDirection.HIGHER_IS_BETTER,
    historicalBaseline: List<DailyDataPoint>? = null,
) {
    val renderData = remember(points) { buildTrendChartRenderData(points) }
    if (renderData.validPoints.isEmpty()) {
        EmptyChartPlaceholder(modifier = modifier)
        return
    }

    val composableState =
        buildTrendChartComposableState(
            TrendChartParams(
                renderData = renderData,
                rangeStartMs = rangeStartMs,
                rangeDays = rangeDays,
                baselineUnit = baselineUnit,
                baseline = baseline,
                baselineLabel = baselineLabel,
                baselineUnavailableLabel = baselineUnavailableLabel,
                baselineDecimalPlaces = baselineDecimalPlaces,
                axisDecimalPlaces = axisDecimalPlaces,
                tooltipDecimalPlaces = tooltipDecimalPlaces,
                showBaseline = showBaseline,
                hideUnitInTooltip = hideUnitInTooltip,
                scrollState = scrollState,
                zoomState = zoomState,
                parentScrollInProgress = parentScrollInProgress,
                granularity = granularity,
                periodSummary = periodSummary,
                deltaDirection = deltaDirection,
                historicalBaseline = historicalBaseline,
                minYOverride = minYOverride,
                maxYOverride = maxYOverride,
                modifier = modifier,
            ),
        )

    TrendChartContent(
        renderData = composableState.renderData,
        config = composableState.config,
        bounds = composableState.bounds,
        uiState = composableState.uiState,
        zoneBands = zoneBands,
        bucketZoneBands = bucketZoneBands,
        historicalBaseline = historicalBaseline,
    )
}

@Composable
private fun defaultZoomState(rangeDays: Int): VicoZoomState =
    rememberVicoZoomState(
        zoomEnabled = rangeDays > 7,
        initialZoom = Zoom.Content,
        minZoom = Zoom.min(Zoom.Content, Zoom.fixed(1f)),
        maxZoom =
            remember(rangeDays) {
                when (rangeDays) {
                    30 -> Zoom.fixed(6f)
                    180 -> Zoom.fixed(25f)
                    360 -> Zoom.fixed(45f)
                    else -> Zoom.fixed(2f)
                }
            },
    )

@Composable
private fun calculateMinMaxY(
    renderData: TrendChartRenderData,
    minYOverride: Double?,
    maxYOverride: Double?,
): Pair<Double, Double> =
    remember(renderData, minYOverride, maxYOverride) {
        val lo = requireNotNull(renderData.minimum)
        val hi = requireNotNull(renderData.maximum)
        val computedMin = kotlin.math.floor(lo * 0.9f).toDouble()
        val computedMax = kotlin.math.ceil(hi * 1.1f).toDouble()
        (minYOverride ?: computedMin) to (maxYOverride ?: computedMax)
    }

@Composable
private fun TrendChartContent(
    renderData: TrendChartRenderData,
    config: TrendChartConfig,
    bounds: TrendChartBounds,
    uiState: TrendChartUIState,
    zoneBands: List<ZoneBand>?,
    bucketZoneBands: List<BucketZoneBands>?,
    historicalBaseline: List<DailyDataPoint>?,
) {
    TrendChartHostBox(
        renderData = renderData,
        config = config,
        bounds = bounds,
        uiState = uiState,
        zoneBands = zoneBands,
        bucketZoneBands = bucketZoneBands,
        historicalBaseline = historicalBaseline,
        modifier = uiState.modifier,
    )

    TrendChartLegendSection(config = config, bounds = bounds)
    TrendChartSummarySection(uiState = uiState, config = config)
}

@Composable
private fun TrendChartLegendSection(
    config: TrendChartConfig,
    bounds: TrendChartBounds,
) {
    if (bounds.shouldShowBaseline || bounds.baselineUnavailableLabel != null || bounds.hasHistoricalBaseline) {
        Spacer(Modifier.height(MaterialTheme.spacing.extraSmallMedium))
        BaselineLegend(
            value = if (bounds.shouldShowBaseline || bounds.hasHistoricalBaseline) bounds.baselineValue else null,
            unit = config.baselineUnit,
            label = bounds.resolvedBaselineLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            decimalPlaces = config.baselineDecimalPlaces,
            unavailableValueLabel = bounds.baselineUnavailableLabel,
        )
    }
}

@Composable
private fun TrendChartSummarySection(
    uiState: TrendChartUIState,
    config: TrendChartConfig,
) {
    if (uiState.periodSummary != null) {
        Spacer(Modifier.height(MaterialTheme.spacing.extraSmallMedium))
        PeriodAverageSummaryRow(
            summary = uiState.periodSummary,
            unit = config.baselineUnit,
            decimalPlaces = config.baselineDecimalPlaces,
            direction = uiState.deltaDirection,
        )
    }
}

@Composable
fun BaselineLegend(
    value: Float?,
    unit: String,
    color: Color,
    modifier: Modifier = Modifier,
    label: String = "Baseline",
    decimalPlaces: Int = 0,
    unavailableValueLabel: String? = null,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(width = 12.dp, height = 2.dp)
                    .background(color),
        )
        Spacer(Modifier.width(MaterialTheme.spacing.small))
        Text(
            text =
                requireNotNull(
                    formatBaselineLegendText(
                        value = value,
                        unit = unit,
                        label = label,
                        decimalPlaces = decimalPlaces,
                        unavailableValueLabel = unavailableValueLabel,
                    ),
                ),
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

@Composable
fun EmptyChartPlaceholder(modifier: Modifier = Modifier) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .height(180.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.BarChart,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(36.dp),
        )
        Spacer(modifier = Modifier.height(MaterialTheme.spacing.small))
        Text(
            text = stringResource(R.string.message_no_data_available),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
