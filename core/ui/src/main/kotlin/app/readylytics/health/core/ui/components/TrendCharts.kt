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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.LocalExtendedColors
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.R
import app.readylytics.health.core.ui.common.ChartUtils
import app.readylytics.health.core.ui.common.DailyDataPoint
import app.readylytics.health.core.ui.common.DeltaDirection
import app.readylytics.health.core.ui.common.PeriodAverageSummary
import app.readylytics.health.core.ui.common.TrendGranularity
import app.readylytics.health.core.ui.common.periodLabelFor
import app.readylytics.health.domain.model.ZoneBand
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.VicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.VicoZoomState
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.decoration.HorizontalLine
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun TrendChart(
    points: List<DailyDataPoint>,
    rangeStartMs: Long,
    rangeDays: Int,
    metricName: String,
    baselineUnit: String,
    modifier: Modifier = Modifier,
    baseline: Float? = null,
    baselineLabel: String? = null,
    baselineUnavailableLabel: String? = null,
    baselineDecimalPlaces: Int = 0,
    axisDecimalPlaces: Int = 0,
    tooltipDecimalPlaces: Int = axisDecimalPlaces,
    showBaseline: Boolean = true,
    // Steps are self-descriptive (the metric title already says "steps"), so the tooltip omits
    // the unit suffix. Gated by this flag rather than comparing baselineUnit's text, since
    // baselineUnit is a localized display string and must not double as a behavior switch.
    hideUnitInTooltip: Boolean = false,
    scrollState: VicoScrollState = rememberVicoScrollState(scrollEnabled = rangeDays > 7),
    // Zoom is only meaningful for ranges > 7 days.
    // initialZoom = Zoom.Content → chart starts fully zoomed out (fit-to-range).
    // minZoom = Zoom.min(Zoom.Content, Zoom.fixed(1f)) → floor is whichever is smaller:
    //   the content zoom (fits all data) or 1×. For 30d (~0.86×) and 180d (~0.14×), content
    //   zoom < 1×, so the floor becomes the content zoom — preventing zoom-out past full view.
    //   Unlike bare Zoom.Content as minZoom, mixing via Zoom.min avoids the circular
    //   constraint that silently rejects pinch-in gestures.
    zoomState: VicoZoomState =
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
        ),
    zoneBands: List<ZoneBand>? = null,
    minYOverride: Double? = null,
    maxYOverride: Double? = null,
    parentScrollInProgress: () -> Boolean = { false },
    granularity: TrendGranularity = TrendGranularity.DAILY,
    periodSummary: PeriodAverageSummary? = null,
    // Whether an increase is favourable for [periodSummary]'s metric (e.g. HRV) or not (e.g. RHR);
    // drives the summary delta arrow color. [DeltaDirection.NEUTRAL] keeps the arrow neutral.
    deltaDirection: DeltaDirection = DeltaDirection.HIGHER_IS_BETTER,
) {
    var tooltipState by remember { mutableStateOf<DataPointTooltipData?>(null) }
    var selectedPointOffset by remember { mutableStateOf<Offset?>(null) }
    val renderData = remember(points) { buildTrendChartRenderData(points) }

    // Clear highlight when tooltip is hidden
    LaunchedEffect(tooltipState) {
        if (tooltipState == null) {
            selectedPointOffset = null
        }
    }

    // Clear tooltip when the chart is scrolled/panned (Vico horizontal scroll)
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.value }.collect {
            tooltipState = null
            selectedPointOffset = null
        }
    }

    // Clear tooltip when the parent list scrolls vertically.
    // parentScrollInProgress is a deferred read: collecting it via snapshotFlow keeps the
    // isScrollInProgress state subscription OUT of the parent's composition scope, so the
    // chart no longer recomposes on every scroll start/stop. snapshotFlow conflates to
    // distinct values, so this still fires on BOTH transitions (true → false → true).
    val currentParentScrollInProgress by rememberUpdatedState(parentScrollInProgress)
    LaunchedEffect(Unit) {
        snapshotFlow { currentParentScrollInProgress() }.collect {
            tooltipState = null
            selectedPointOffset = null
        }
    }

    val resolvedBaselineLabel = baselineLabel ?: stringResource(R.string.label_baseline)

    if (renderData.validPoints.isEmpty()) {
        EmptyChartPlaceholder(modifier = modifier)
        return
    }

    val calculatedBaseline = requireNotNull(renderData.calculatedBaseline)

    // Use provided baseline if available, otherwise fall back to calculated baseline
    val baselineValue = baseline ?: calculatedBaseline

    val (minY, maxY) =
        remember(renderData, minYOverride, maxYOverride) {
            val lo = requireNotNull(renderData.minimum)
            val hi = requireNotNull(renderData.maximum)
            val computedMin = kotlin.math.floor(lo * 0.9f).toDouble()
            val computedMax = kotlin.math.ceil(hi * 1.1f).toDouble()
            (minYOverride ?: computedMin) to (maxYOverride ?: computedMax)
        }

    val shouldShowBaseline =
        remember(baselineValue, minY, maxY, showBaseline) {
            showBaseline && baselineValue.toDouble() >= minY && baselineValue.toDouble() <= maxY
        }
    val labelComponent = ChartDefaults.labelTextComponent()
    val axisLabelComponent = ChartDefaults.axisLabelTextComponent()
    val baselineColor = MaterialTheme.colorScheme.onSurfaceVariant
    val guidelineComponent = ChartDefaults.guidelineComponent()
    val dotColor = MaterialTheme.colorScheme.primary

    val modelProducer = remember { CartesianChartModelProducer() }

    val xAxisFormatter = ChartDefaults.rememberPeriodFormatter(rangeStartMs, granularity)

    // Resolved in composable scope so the format string can be used inside the non-composable
    // marker listener below; resource strings must never be built as Kotlin literals.
    val quarterTemplate = stringResource(R.string.period_label_quarter)

    LaunchedEffect(renderData.validPoints) {
        modelProducer.runTransaction {
            lineModel {
                series(
                    x = renderData.validPoints.map(DailyDataPoint::dayOffset),
                    y = renderData.validPoints.map { requireNotNull(it.value).toDouble() },
                )
            }
        }
    }

    // Note: Vico scroll positioning is handled through initial rememberVicoScrollState
    // Auto-scroll on range change would require accessing internal Vico APIs

    val rangeProvider =
        remember(minY, maxY, rangeDays) {
            CartesianLayerRangeProvider.fixed(
                minX = 0.0,
                maxX = (rangeDays - 1).toDouble(),
                minY = minY,
                maxY = maxY,
            )
        }
    val dotComponent = rememberShapeComponent(fill = Fill(dotColor), shape = CircleShape)
    val lineFill = remember(dotColor) { LineCartesianLayer.LineFill.single(Fill(dotColor)) }
    val areaFill =
        remember(dotColor) {
            LineCartesianLayer.AreaFill.single(
                Fill(
                    brush =
                        Brush.verticalGradient(
                            colors = listOf(dotColor.copy(alpha = 0.3f), dotColor.copy(alpha = 0.0f)),
                        ),
                ),
            )
        }
    val line =
        LineCartesianLayer.rememberLine(
            fill = lineFill,
            areaFill = areaFill,
            pointProvider =
                LineCartesianLayer.PointProvider.single(
                    LineCartesianLayer.Point(dotComponent, 6.dp),
                ),
            interpolator = LineCartesianLayer.Interpolator.cubic(0.2f),
        )

    val extendedColors = LocalExtendedColors.current
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val errorContainer = MaterialTheme.colorScheme.errorContainer
    val chartZoneBands = zoneBands ?: emptyList()
    val colors = rememberZoneBandColors(chartZoneBands, extendedColors, primaryContainer, errorContainer)
    val zoneBandDecoration =
        remember(chartZoneBands, colors, minY, maxY) {
            ZoneBandDecoration(chartZoneBands, colors, minY, maxY)
        }

    val markerVisibilityListener =
        rememberChartMarkerVisibilityListener { x, _, canvasX, canvasY ->
            if (!shouldProcessTrendMarker(currentParentScrollInProgress())) {
                return@rememberChartMarkerVisibilityListener
            }

            val dayOffset = x.toInt()
            val nearest = renderData.pointByDayOffset[dayOffset]
            val date = ChartUtils.dayOffsetToLocalDate(dayOffset, rangeStartMs)
            val valueText =
                formatTrendTooltipValue(
                    value = nearest?.value,
                    decimalPlaces = tooltipDecimalPlaces,
                    hideUnit = hideUnitInTooltip,
                    unit = baselineUnit,
                )
            val nextOffset = Offset(canvasX, canvasY)
            val nextTooltip =
                DataPointTooltipData(
                    valueText = valueText,
                    dateText =
                        formatTrendTooltipDate(
                            granularity,
                            date,
                        ) { quarter -> String.format(Locale.getDefault(), quarterTemplate, quarter) },
                    offset =
                        androidx.compose.ui.unit
                            .IntOffset(canvasX.toInt(), canvasY.toInt()),
                )
            if (shouldAssignTrendMarkerState(selectedPointOffset, nextOffset)) {
                selectedPointOffset = nextOffset
            }
            if (shouldAssignTrendMarkerState(tooltipState, nextTooltip)) {
                tooltipState = nextTooltip
            }
        }

    val lineProvider = remember(line) { LineCartesianLayer.LineProvider.series(line) }

    val startAxisValueFormatter =
        remember(axisDecimalPlaces) {
            CartesianValueFormatter { _, value, _ ->
                if (axisDecimalPlaces == 0) {
                    value.roundToInt().toString()
                } else {
                    String.format("%.${axisDecimalPlaces}f", value)
                }
            }
        }

    val baselineLineComponent = rememberLineComponent(fill = Fill(baselineColor), thickness = 1.dp)
    val decorations =
        remember(zoneBandDecoration, shouldShowBaseline, baselineValue, baselineLineComponent) {
            listOfNotNull(
                zoneBandDecoration,
                if (shouldShowBaseline) {
                    HorizontalLine(
                        y = { baselineValue.toDouble() },
                        line = baselineLineComponent,
                    )
                } else {
                    null
                },
            )
        }

    Box(
        modifier = modifier.fillMaxWidth(),
    ) {
        CartesianChartHost(
            chart =
                rememberCartesianChart(
                    rememberLineCartesianLayer(
                        lineProvider = lineProvider,
                        rangeProvider = rangeProvider,
                    ),
                    startAxis =
                        VerticalAxis.rememberStart(
                            label = labelComponent,
                            valueFormatter = startAxisValueFormatter,
                            guideline = guidelineComponent,
                            title = { baselineUnit },
                            titleComponent = axisLabelComponent,
                        ),
                    bottomAxis =
                        HorizontalAxis.rememberBottom(
                            label = labelComponent,
                            valueFormatter = xAxisFormatter,
                            itemPlacer =
                                remember(rangeDays, renderData.validPoints) {
                                    ChartDefaults.itemPlacerForRangeDays(
                                        rangeDays = rangeDays,
                                        pointOffsets =
                                            if (granularity == TrendGranularity.DAILY) {
                                                emptyList()
                                            } else {
                                                renderData.validPoints.map(DailyDataPoint::dayOffset)
                                            },
                                    )
                                },
                            guideline = guidelineComponent,
                        ),
                    decorations = decorations,
                    marker = InvisibleMarker,
                    markerVisibilityListener = markerVisibilityListener,
                ),
            modelProducer = modelProducer,
            scrollState = scrollState,
            zoomState = zoomState,
            modifier = Modifier.fillMaxWidth().height(180.dp),
        )

        VicoChartTooltipOverlay(
            selectedPointOffset = selectedPointOffset,
            pulseColor = dotColor,
            modifier = Modifier.fillMaxWidth().height(180.dp),
        )

        if (tooltipState != null) {
            DataPointTooltip(
                isVisible = true,
                data = tooltipState!!,
                onDismissRequest = { tooltipState = null },
            )
        }
    }

    if (shouldShowBaseline || baselineUnavailableLabel != null) {
        Spacer(Modifier.height(MaterialTheme.spacing.extraSmallMedium))
        BaselineLegend(
            value = if (shouldShowBaseline) baselineValue else null,
            unit = baselineUnit,
            label = resolvedBaselineLabel,
            color = baselineColor,
            decimalPlaces = baselineDecimalPlaces,
            unavailableValueLabel = baselineUnavailableLabel,
        )
    }

    if (periodSummary != null) {
        Spacer(Modifier.height(MaterialTheme.spacing.extraSmallMedium))
        PeriodAverageSummaryRow(
            summary = periodSummary,
            unit = baselineUnit,
            decimalPlaces = baselineDecimalPlaces,
            direction = deltaDirection,
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

internal fun formatTrendTooltipValue(
    value: Float?,
    decimalPlaces: Int,
    hideUnit: Boolean,
    unit: String,
): String {
    if (value == null) return "—"
    val formatted =
        if (decimalPlaces == 0) {
            value.roundToInt().toString()
        } else {
            String.format(Locale.getDefault(), "%.${decimalPlaces}f", value)
        }
    return if (hideUnit) formatted else "$formatted $unit"
}

/**
 * Tooltip date line: [DAILY] keeps the short date format; bucketed [MONTHLY]/[QUARTERLY] points
 * show the containing period label (e.g. "Juli" / "Q3") instead of a mid-month calendar date.
 * The quarterly label comes from the caller (a `strings.xml` resource), keeping this pure.
 */
internal fun formatTrendTooltipDate(
    granularity: TrendGranularity,
    date: java.time.LocalDate,
    quarterLabel: (Int) -> String,
): String =
    if (granularity == TrendGranularity.DAILY) {
        ChartUtils.formatTooltipDate(date)
    } else {
        periodLabelFor(granularity, date, quarterLabel)
    }

internal fun formatBaselineLegendText(
    value: Float?,
    unit: String,
    label: String,
    decimalPlaces: Int,
    unavailableValueLabel: String?,
): String? =
    when {
        value != null -> {
            val formattedValue =
                if (decimalPlaces == 0) value.roundToInt().toString() else String.format("%.${decimalPlaces}f", value)
            "$label: $formattedValue $unit"
        }
        unavailableValueLabel != null -> "$label: $unavailableValueLabel"
        else -> null
    }

internal fun shouldProcessTrendMarker(parentScrollInProgress: Boolean): Boolean = !parentScrollInProgress

internal fun <T> shouldAssignTrendMarkerState(
    current: T,
    next: T,
): Boolean = current != next
