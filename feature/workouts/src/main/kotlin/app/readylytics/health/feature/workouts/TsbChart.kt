package app.readylytics.health.feature.workouts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.LocalExtendedColors
import app.readylytics.health.core.model.domain.model.HealthZone
import app.readylytics.health.core.model.domain.model.ZoneBand
import app.readylytics.health.core.ui.common.ChartUtils
import app.readylytics.health.core.ui.common.DailyDataPoint
import app.readylytics.health.core.ui.common.TrendGranularity
import app.readylytics.health.core.ui.common.periodLabelFor
import app.readylytics.health.core.ui.common.rememberPeriodOrdinalLabel
import app.readylytics.health.core.ui.components.ChartDefaults
import app.readylytics.health.core.ui.components.ChartZoneAlphas
import app.readylytics.health.core.ui.components.ZoneBandDecoration
import app.readylytics.health.feature.workouts.R
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.VicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.VicoZoomState
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.decoration.Decoration
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.data.ExtraStore
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

// Mirrors the zone boundaries in TrainingStressBalanceCalculator (core/scoring). Kept as local
// named constants here since the calculator does not expose them as a shared constants object.
private const val VERY_FRESH_THRESHOLD = 25f
private const val FRESH_THRESHOLD = 5f
private const val FATIGUED_THRESHOLD = -10f
private const val HIGH_RISK_THRESHOLD = -30f
private const val Y_AXIS_PADDING = 5f
private const val Y_AXIS_STEP = 10f

@Composable
private fun rememberTsbDecorations(
    chartMinY: Double,
    chartMaxY: Double,
): List<Decoration> {
    val extendedColors = LocalExtendedColors.current
    val colorScheme = MaterialTheme.colorScheme
    val tsbZoneBands =
        remember {
            listOf(
                ZoneBand(
                    lowerBound = VERY_FRESH_THRESHOLD.toDouble(),
                    upperBound = Double.POSITIVE_INFINITY,
                    zone = HealthZone.NEUTRAL,
                ),
                ZoneBand(
                    lowerBound = FRESH_THRESHOLD.toDouble(),
                    upperBound = VERY_FRESH_THRESHOLD.toDouble(),
                    zone = HealthZone.OPTIMAL,
                ),
                ZoneBand(
                    lowerBound = FATIGUED_THRESHOLD.toDouble(),
                    upperBound = FRESH_THRESHOLD.toDouble(),
                    zone = HealthZone.OPTIMAL,
                ),
                ZoneBand(
                    lowerBound = HIGH_RISK_THRESHOLD.toDouble(),
                    upperBound = FATIGUED_THRESHOLD.toDouble(),
                    zone = HealthZone.WARNING,
                ),
                ZoneBand(
                    lowerBound = Double.NEGATIVE_INFINITY,
                    upperBound = HIGH_RISK_THRESHOLD.toDouble(),
                    zone = HealthZone.CRITICAL,
                ),
            )
        }
    val bandColors =
        remember(extendedColors, colorScheme) {
            listOf(
                extendedColors.neutralContainer.copy(alpha = ChartZoneAlphas.RESTING),
                colorScheme.tertiaryContainer.copy(alpha = ChartZoneAlphas.MODERATE),
                colorScheme.primaryContainer.copy(alpha = ChartZoneAlphas.HIGH),
                extendedColors.warningContainer.copy(alpha = ChartZoneAlphas.HIGH),
                colorScheme.errorContainer.copy(alpha = ChartZoneAlphas.HIGH),
            )
        }

    val decoration =
        remember(tsbZoneBands, bandColors, chartMinY, chartMaxY) {
            ZoneBandDecoration(
                zoneBands = tsbZoneBands,
                bandColors = bandColors,
                minY = chartMinY,
                maxY = chartMaxY,
            )
        }

    return listOf(decoration)
}

@Composable
internal fun rememberTsbYBounds(remappedPoints: List<DailyDataPoint>): Pair<Double, Double> =
    remember(remappedPoints) {
        val dataMin = remappedPoints.mapNotNull { it.value }.minOrNull() ?: HIGH_RISK_THRESHOLD
        val dataMax = remappedPoints.mapNotNull { it.value }.maxOrNull() ?: VERY_FRESH_THRESHOLD
        val minY = (floor(minOf(dataMin, HIGH_RISK_THRESHOLD - Y_AXIS_PADDING) / Y_AXIS_STEP) * Y_AXIS_STEP).toDouble()
        val maxY = (ceil(maxOf(dataMax, VERY_FRESH_THRESHOLD + Y_AXIS_PADDING) / Y_AXIS_STEP) * Y_AXIS_STEP).toDouble()
        minY to maxY
    }

@Composable
private fun rememberTsbRangeProvider(
    xAxisRangeDays: Int,
    chartMinY: Double,
    chartMaxY: Double,
): CartesianLayerRangeProvider =
    remember(xAxisRangeDays, chartMinY, chartMaxY) {
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

            override fun getMinY(
                minY: Double,
                maxY: Double,
                extraStore: ExtraStore,
            ) = chartMinY

            override fun getMaxY(
                minY: Double,
                maxY: Double,
                extraStore: ExtraStore,
            ) = chartMaxY
        }
    }

@Composable
private fun rememberTsbModelProducer(remappedPoints: List<DailyDataPoint>): CartesianChartModelProducer {
    val modelProducer = remember { CartesianChartModelProducer() }
    LaunchedEffect(remappedPoints) {
        modelProducer.runTransaction {
            val valid = remappedPoints.filter { it.value != null }
            if (valid.isNotEmpty()) {
                lineModel {
                    series(
                        x = valid.map { it.dayOffset },
                        y = valid.mapNotNull { it.value?.toDouble() },
                    )
                }
            }
        }
    }
    return modelProducer
}

@Composable
private fun rememberTsbLine(): LineCartesianLayer.Line {
    val lineColor = MaterialTheme.colorScheme.primary
    return LineCartesianLayer.rememberLine(
        fill = LineCartesianLayer.LineFill.single(Fill(lineColor)),
        areaFill =
            LineCartesianLayer.AreaFill.single(
                Fill(
                    brush =
                        Brush.verticalGradient(
                            colors = listOf(lineColor.copy(alpha = 0.3f), lineColor.copy(alpha = 0.0f)),
                        ),
                ),
            ),
        interpolator = LineCartesianLayer.Interpolator.cubic(0.2f),
    )
}

@Composable
private fun rememberTsbXAxisFormatter(
    tsbPoints: List<DailyDataPoint>,
    rangeStartMs: Long,
    granularity: TrendGranularity,
): CartesianValueFormatter {
    val ordinalLabel = rememberPeriodOrdinalLabel(granularity)
    val periodLabels =
        remember(tsbPoints, rangeStartMs, granularity, ordinalLabel) {
            if (granularity == TrendGranularity.DAILY) {
                emptyList()
            } else {
                tsbPoints.map { point ->
                    val date = ChartUtils.dayOffsetToLocalDate(point.dayOffset, rangeStartMs)
                    periodLabelFor(granularity, date, ordinalLabel)
                }
            }
        }
    return if (granularity == TrendGranularity.DAILY) {
        ChartDefaults.rememberPeriodFormatter(rangeStartMs, granularity)
    } else {
        val fallback = periodLabels.firstOrNull().orEmpty()
        remember(periodLabels) {
            CartesianValueFormatter { _, value, _ -> periodLabels.getOrElse(value.toInt()) { fallback } }
        }
    }
}

@Composable
private fun rememberTsbRemappedPoints(
    tsbPoints: List<DailyDataPoint>,
    granularity: TrendGranularity,
): List<DailyDataPoint> =
    remember(tsbPoints, granularity) {
        if (granularity == TrendGranularity.DAILY) tsbPoints else tsbPoints.mapIndexed { i, p -> p.copy(dayOffset = i) }
    }

private data class TsbChartHostConfig(
    val rangeProvider: CartesianLayerRangeProvider,
    val xAxisFormatter: CartesianValueFormatter,
    val itemPlacer: com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis.ItemPlacer,
    val modelProducer: CartesianChartModelProducer,
    val hasData: Boolean,
    val decorations: List<Decoration>,
)

@Composable
private fun TsbChartHost(
    config: TsbChartHostConfig,
    scrollState: VicoScrollState,
    zoomState: VicoZoomState,
    modifier: Modifier,
) {
    val tsbLine = rememberTsbLine()
    val yAxisFormatter =
        remember {
            CartesianValueFormatter { _, value, _ -> String.format(Locale.US, "%+d", value.roundToInt()) }
        }
    val labelComponent = ChartDefaults.labelTextComponent()
    val axisLabelComponent = ChartDefaults.axisLabelTextComponent()
    val guidelineComponent = ChartDefaults.guidelineComponent()
    val tsbTitle = stringResource(R.string.training_load_metric_tsb)
    val chartModifier = if (config.hasData) modifier.testTag("TsbChart") else modifier
    val chartHeight = 220.dp

    Box(modifier = chartModifier.fillMaxWidth()) {
        CartesianChartHost(
            chart =
                rememberCartesianChart(
                    rememberLineCartesianLayer(
                        lineProvider = LineCartesianLayer.LineProvider.series(tsbLine),
                        rangeProvider = config.rangeProvider,
                    ),
                    startAxis =
                        VerticalAxis.rememberStart(
                            label = labelComponent,
                            valueFormatter = yAxisFormatter,
                            titleComponent = axisLabelComponent,
                            title = { tsbTitle },
                            guideline = guidelineComponent,
                        ),
                    bottomAxis =
                        HorizontalAxis.rememberBottom(
                            label = labelComponent,
                            valueFormatter = config.xAxisFormatter,
                            itemPlacer = config.itemPlacer,
                            guideline = guidelineComponent,
                        ),
                    decorations = config.decorations,
                ),
            modelProducer = config.modelProducer,
            scrollState = scrollState,
            zoomState = zoomState,
            modifier = Modifier.fillMaxWidth().height(chartHeight),
        )
    }
}

/**
 * Zero-centered Training Stress Balance (TSB = CTL - ATL) line chart. Background zone band colors
 * mark the [app.readylytics.health.core.model.domain.cardio.TsbZone] boundaries so the current
 * trend can be read against "very fresh / fresh / optimal / fatigued / high risk" at a glance.
 */
@Composable
internal fun TsbChart(
    tsbPoints: List<DailyDataPoint>,
    rangeStartMs: Long,
    rangeDays: Int,
    scrollState: VicoScrollState,
    zoomState: VicoZoomState,
    modifier: Modifier = Modifier,
    granularity: TrendGranularity = TrendGranularity.DAILY,
) {
    val remappedPoints = rememberTsbRemappedPoints(tsbPoints, granularity)
    val xAxisRangeDays =
        remember(tsbPoints, granularity) {
            if (granularity == TrendGranularity.DAILY) rangeDays else tsbPoints.size
        }

    val (minY, maxY) = rememberTsbYBounds(remappedPoints)
    val rangeProvider = rememberTsbRangeProvider(xAxisRangeDays, minY, maxY)
    val decorations = rememberTsbDecorations(minY, maxY)
    val modelProducer = rememberTsbModelProducer(remappedPoints)
    val xAxisFormatter = rememberTsbXAxisFormatter(tsbPoints, rangeStartMs, granularity)

    val itemPlacer =
        ChartDefaults.rememberTrendAxisItemPlacer(
            rangeDays = xAxisRangeDays,
            granularity = granularity,
            rangeStartMs = rangeStartMs,
            explicitPointOffsets =
                if (granularity == TrendGranularity.DAILY) {
                    emptyList()
                } else {
                    remappedPoints.map { it.dayOffset }.distinct().sorted()
                },
        )

    val hasData = remember(remappedPoints) { remappedPoints.any { it.value != null } }

    TsbChartHost(
        config =
            TsbChartHostConfig(
                rangeProvider = rangeProvider,
                xAxisFormatter = xAxisFormatter,
                itemPlacer = itemPlacer,
                modelProducer = modelProducer,
                hasData = hasData,
                decorations = decorations,
            ),
        scrollState = scrollState,
        zoomState = zoomState,
        modifier = modifier,
    )
}
