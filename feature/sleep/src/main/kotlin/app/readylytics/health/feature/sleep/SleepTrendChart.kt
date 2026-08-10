package app.readylytics.health.feature.sleep

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.common.ChartUtils
import app.readylytics.health.core.ui.common.DailyDataPoint
import app.readylytics.health.core.ui.common.DateFormatUtils
import app.readylytics.health.core.ui.common.PeriodAverageSummary
import app.readylytics.health.core.ui.common.SkeletonCard
import app.readylytics.health.core.ui.common.TimeRange
import app.readylytics.health.core.ui.common.TrendGranularity
import app.readylytics.health.core.ui.common.periodLabelFor
import app.readylytics.health.core.ui.components.ChartDefaults
import app.readylytics.health.core.ui.components.DataPointTooltip
import app.readylytics.health.domain.scoring.sleep.SleepTrendDay
import app.readylytics.health.feature.sleep.R
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
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.data.ExtraStore
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import app.readylytics.health.core.ui.R as CoreUiR

@Composable
fun SleepTrendCard(
    selectedRange: TimeRange,
    startOffsetPoints: List<DailyDataPoint>,
    durationSpanPoints: List<DailyDataPoint>,
    actualDurationPoints: List<DailyDataPoint>,
    trendDays: List<SleepTrendDay>,
    rangeStartMs: Long,
    scrollState: VicoScrollState,
    zoomState: VicoZoomState,
    modifier: Modifier = Modifier,
    parentScrollInProgress: () -> Boolean = { false },
    scoringZoneId: ZoneId = ZoneId.systemDefault(),
    actualDurationSummary: PeriodAverageSummary? = null,
) {
    val barColor = MaterialTheme.colorScheme.primary
    val lineColor = MaterialTheme.colorScheme.tertiary
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.padding(MaterialTheme.spacing.medium)) {
            Text(
                text = stringResource(R.string.sleep_trend_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(MaterialTheme.spacing.medium))

            SleepTrendChart(
                selectedRange = selectedRange,
                startOffsetPoints = startOffsetPoints,
                durationSpanPoints = durationSpanPoints,
                actualDurationPoints = actualDurationPoints,
                trendDays = trendDays,
                rangeStartMs = rangeStartMs,
                scrollState = scrollState,
                zoomState = zoomState,
                parentScrollInProgress = parentScrollInProgress,
                scoringZoneId = scoringZoneId,
            )

            Spacer(Modifier.height(MaterialTheme.spacing.medium))
            SleepTrendChartLegends(
                barColor = barColor,
                lineColor = lineColor,
            )

            actualDurationSummary?.let { summary ->
                Spacer(Modifier.height(MaterialTheme.spacing.small))
                val avg = summary.average
                val prev = summary.previousAverage
                if (avg != null) {
                    val avgMinutes = (avg * 60f).roundToInt()
                    val durationText = DateFormatUtils.formatSleepDuration(avgMinutes)

                    val quarterTemplate = stringResource(CoreUiR.string.period_label_quarter)
                    val periodLabel =
                        periodLabelFor(summary.granularity, summary.periodStartDate) { quarter ->
                            String.format(Locale.getDefault(), quarterTemplate, quarter)
                        }
                    val avgLabel = stringResource(CoreUiR.string.label_avg)

                    val previousLabel =
                        periodLabelFor(summary.granularity, summary.previousPeriodStartDate) { quarter ->
                            String.format(Locale.getDefault(), quarterTemplate, quarter)
                        }
                    val previousLabelText = stringResource(CoreUiR.string.period_summary_vs, previousLabel)

                    val deltaMinutes = prev?.let { ((avg - it) * 60f).roundToInt() }
                    val deltaText =
                        if (deltaMinutes != null && deltaMinutes != 0) {
                            val sign = if (deltaMinutes > 0) "+" else ""
                            val absMin = kotlin.math.abs(deltaMinutes)
                            stringResource(R.string.sleep_trend_avg_delta_minutes_format, sign, absMin)
                        } else {
                            stringResource(R.string.sleep_trend_avg_delta_no_change)
                        }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
                    ) {
                        Text(
                            text = "$periodLabel $avgLabel: $durationText",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = deltaText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = previousLabelText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SleepTrendChart(
    selectedRange: TimeRange,
    startOffsetPoints: List<DailyDataPoint>,
    durationSpanPoints: List<DailyDataPoint>,
    actualDurationPoints: List<DailyDataPoint>,
    trendDays: List<SleepTrendDay>,
    rangeStartMs: Long,
    scrollState: VicoScrollState,
    zoomState: VicoZoomState,
    modifier: Modifier = Modifier,
    parentScrollInProgress: () -> Boolean = { false },
    scoringZoneId: ZoneId = ZoneId.systemDefault(),
) {
    val rangeDays = selectedRange.days
    var selectedState by
        remember(
            startOffsetPoints,
            durationSpanPoints,
            actualDurationPoints,
            trendDays,
            rangeStartMs,
        ) {
            mutableStateOf<SleepTrendSelectedState?>(null)
        }

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

    val durationFormat = stringResource(R.string.sleep_trend_tooltip_duration_format)
    val bedtimeFormat = stringResource(R.string.sleep_trend_tooltip_bedtime_format)
    val napsHeading = stringResource(R.string.sleep_trend_tooltip_naps_heading)
    val napItemFormat = stringResource(R.string.sleep_trend_tooltip_nap_item_format)
    val avgDurationFormat = stringResource(R.string.sleep_trend_tooltip_avg_duration_format)
    val avgBedtimeFormat = stringResource(R.string.sleep_trend_tooltip_avg_bedtime_format)
    val quarterLabelFormat = stringResource(R.string.sleep_trend_tooltip_quarter_format)
    val hoursOnlyFormat = stringResource(CoreUiR.string.sleep_duration_hours_only)
    val context = LocalContext.current
    val clockFormatter = remember(context) { DateFormat.getTimeFormat(context) }
    val granularity = selectedRange.granularity

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

    val periodLabels = remember(startOffsetPoints, rangeStartMs, granularity, scoringZoneId, quarterLabelFormat) {
        if (granularity == TrendGranularity.DAILY) {
            emptyList()
        } else {
            startOffsetPoints.map { point ->
                val date = ChartUtils.dayOffsetToLocalDate(point.dayOffset, rangeStartMs, scoringZoneId)
                periodLabelFor(granularity, date) { quarter ->
                    String.format(Locale.getDefault(), quarterLabelFormat, quarter)
                }
            }
        }
    }

    val tooltipState =
        remember(
            selectedState,
            rangeStartMs,
            scoringZoneId,
            durationFormat,
            bedtimeFormat,
            napsHeading,
            napItemFormat,
            avgDurationFormat,
            avgBedtimeFormat,
            quarterLabelFormat,
            clockFormatter,
            granularity,
            periodLabels,
        ) {
            selectedState?.let { state ->
                buildSleepTrendTooltipData(
                    selectedState = state,
                    rangeStartMs = rangeStartMs,
                    scoringZoneId = scoringZoneId,
                    clockFormatter = clockFormatter,
                    strings =
                        SleepTrendTooltipStrings(
                            durationFormat = durationFormat,
                            bedtimeFormat = bedtimeFormat,
                            napsHeading = napsHeading,
                            napItemFormat = napItemFormat,
                            avgDurationFormat = avgDurationFormat,
                            avgBedtimeFormat = avgBedtimeFormat,
                            quarterLabelFormat = quarterLabelFormat,
                        ),
                    granularity = granularity,
                    periodLabels = periodLabels,
                )
            }
        }

    val labelComponent = ChartDefaults.labelTextComponent()
    val axisLabelComponent = ChartDefaults.axisLabelTextComponent()
    val guidelineComponent = ChartDefaults.guidelineComponent()

    val remappedStartPoints = remember(startOffsetPoints, granularity) {
        if (granularity == TrendGranularity.DAILY) startOffsetPoints
        else startOffsetPoints.mapIndexed { i, p -> p.copy(dayOffset = i) }
    }
    val remappedSpanPoints = remember(durationSpanPoints, granularity) {
        if (granularity == TrendGranularity.DAILY) durationSpanPoints
        else durationSpanPoints.mapIndexed { i, p -> p.copy(dayOffset = i) }
    }
    val remappedActualPoints = remember(actualDurationPoints, granularity) {
        if (granularity == TrendGranularity.DAILY) actualDurationPoints
        else actualDurationPoints.mapIndexed { i, p -> p.copy(dayOffset = i) }
    }
    val xAxisRangeDays = remember(startOffsetPoints, granularity) {
        if (granularity == TrendGranularity.DAILY) rangeDays
        else startOffsetPoints.size
    }

    val leftAxisFormatter =
        remember {
            CartesianValueFormatter { _, value, _ ->
                val hour = (12 + value.roundToInt()).mod(24)
                val amPm = if (hour >= 12) "PM" else "AM"
                val displayHour =
                    when {
                        hour == 0 -> 12
                        hour > 12 -> hour - 12
                        else -> hour
                    }
                String.format(Locale.getDefault(), "%d %s", displayHour, amPm)
            }
        }

    val rightAxisFormatter =
        remember(hoursOnlyFormat) {
            CartesianValueFormatter { _, value, _ ->
                String.format(Locale.getDefault(), hoursOnlyFormat, value.roundToInt())
            }
        }

    val modelProducer = remember { CartesianChartModelProducer() }

    val leftRangeProvider =
        remember(remappedStartPoints, remappedSpanPoints, xAxisRangeDays) {
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
                ): Double {
                    val startVals = remappedStartPoints.mapNotNull { it.value }
                    val minVal = startVals.minOrNull() ?: 8.0f
                    return (floor(minVal.toDouble() - 1.0)).coerceAtLeast(0.0)
                }

                override fun getMaxY(
                    minY: Double,
                    maxY: Double,
                    extraStore: ExtraStore,
                ): Double {
                    val endVals =
                        remappedStartPoints
                            .zip(remappedSpanPoints) { start, span ->
                                val startVal = start.value
                                val spanVal = span.value
                                if (startVal != null && spanVal != null) startVal + spanVal else null
                            }.filterNotNull()
                    val maxVal = endVals.maxOrNull() ?: 20.0f
                    return (ceil(maxVal.toDouble() + 1.0)).coerceAtMost(24.0)
                }
            }
        }

    val rightRangeProvider =
        remember(remappedActualPoints, xAxisRangeDays) {
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
                ) = 0.0

                override fun getMaxY(
                    minY: Double,
                    maxY: Double,
                    extraStore: ExtraStore,
                ): Double {
                    val vals = remappedActualPoints.mapNotNull { it.value?.toDouble() }
                    val maxVal = vals.maxOrNull() ?: 8.0
                    return (ceil(maxVal / 2.0) * 2.0).coerceAtLeast(10.0)
                }
            }
        }

    val xAxisFormatter =
        if (granularity == TrendGranularity.DAILY) {
            ChartDefaults.rememberPeriodFormatter(rangeStartMs, granularity, scoringZoneId)
        } else {
            remember(periodLabels) {
                val fallback = periodLabels.firstOrNull().orEmpty()
                CartesianValueFormatter { _, value, _ ->
                    periodLabels.getOrElse(value.toInt()) { fallback }
                }
            }
        }

    val hasData =
        remember(remappedStartPoints, remappedSpanPoints, remappedActualPoints) {
            remappedStartPoints.any { it.value != null } ||
                remappedSpanPoints.any { it.value != null } ||
                remappedActualPoints.any { it.value != null }
        }

    LaunchedEffect(remappedStartPoints, remappedSpanPoints, remappedActualPoints) {
        modelProducer.runTransaction {
            val validStart = remappedStartPoints.filter { it.value != null }
            val validSpan = remappedSpanPoints.filter { it.value != null }
            if (validStart.isNotEmpty() && validSpan.isNotEmpty()) {
                columnModel {
                    series(
                        x = validStart.map { it.dayOffset },
                        y = validStart.mapNotNull { it.value?.toDouble() },
                    )
                    series(
                        x = validSpan.map { it.dayOffset },
                        y = validSpan.mapNotNull { it.value?.toDouble() },
                    )
                }
            }
            val validActual = remappedActualPoints.filter { it.value != null }
            if (validActual.isNotEmpty()) {
                lineModel {
                    series(
                        x = validActual.map { it.dayOffset },
                        y = validActual.mapNotNull { it.value?.toDouble() },
                    )
                }
            }
        }
    }

    val columnLayer =
        rememberColumnCartesianLayer(
            columnProvider =
                ColumnCartesianLayer.ColumnProvider.series(
                    rememberLineComponent(
                        fill = Fill(Color.Transparent),
                        thickness = 8.dp,
                    ),
                    rememberLineComponent(
                        fill = Fill(MaterialTheme.colorScheme.primary),
                        thickness = 8.dp,
                        shape = CircleShape,
                    ),
                ),
            mergeMode = { ColumnCartesianLayer.MergeMode.Stacked },
            rangeProvider = leftRangeProvider,
            verticalAxisPosition = Axis.Position.Vertical.Start,
        )

    val lineLayer =
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
                                                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f),
                                                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.0f),
                                                ),
                                        ),
                                ),
                            ),
                        pointProvider =
                            LineCartesianLayer.PointProvider.single(
                                LineCartesianLayer.Point(
                                    rememberShapeComponent(
                                        fill = Fill(MaterialTheme.colorScheme.tertiary),
                                        shape = CircleShape,
                                    ),
                                    6.dp,
                                ),
                            ),
                        interpolator = LineCartesianLayer.Interpolator.cubic(0.2f),
                    ),
                ),
            rangeProvider = rightRangeProvider,
            verticalAxisPosition = Axis.Position.Vertical.End,
        )

    val markerVisibilityListener =
        rememberSleepTrendMarkerVisibilityListener(
            startOffsetPoints = remappedStartPoints,
            durationSpanPoints = remappedSpanPoints,
            actualDurationPoints = remappedActualPoints,
            trendDays = trendDays,
            onStateChanged = { selectedState = it },
        )

    val chartHeight = 220.dp
    if (!hasData) {
        Box(
            modifier =
                modifier
                    .fillMaxWidth()
                    .height(chartHeight),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(CoreUiR.string.message_no_data_available),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        Box(
            modifier =
                modifier
                    .fillMaxWidth()
                    .testTag("SleepTrendChart"),
        ) {
            CartesianChartHost(
                chart =
                    rememberCartesianChart(
                        columnLayer,
                        lineLayer,
                        startAxis =
                            VerticalAxis.rememberStart(
                                label = labelComponent,
                                valueFormatter = leftAxisFormatter,
                                guideline = guidelineComponent,
                                itemPlacer = remember { VerticalAxis.ItemPlacer.count(count = { 5 }) },
                            ),
                        bottomAxis =
                            HorizontalAxis.rememberBottom(
                                label = labelComponent,
                                valueFormatter = xAxisFormatter,
                                itemPlacer =
                                    remember(xAxisRangeDays, remappedStartPoints, granularity) {
                                        val offsets =
                                            if (granularity == TrendGranularity.DAILY) {
                                                emptyList()
                                            } else {
                                                remappedStartPoints
                                                    .map { it.dayOffset }
                                                    .distinct().sorted()
                                            }
                                        ChartDefaults.itemPlacerForRangeDays(xAxisRangeDays, offsets)
                                    },
                                guideline = guidelineComponent,
                            ),
                        endAxis =
                            VerticalAxis.rememberEnd(
                                label = labelComponent,
                                valueFormatter = rightAxisFormatter,
                                itemPlacer = remember { VerticalAxis.ItemPlacer.count(count = { 5 }) },
                            ),
                        marker = invisibleMarker,
                        markerVisibilityListener = markerVisibilityListener,
                    ),
                modelProducer = modelProducer,
                scrollState = scrollState,
                zoomState = zoomState,
                modifier = Modifier.fillMaxWidth().height(chartHeight),
            )

            SleepTrendOverlay(
                selectedState = selectedState,
                barColor = MaterialTheme.colorScheme.primary,
                lineColor = MaterialTheme.colorScheme.tertiary,
                layerBounds = layerBounds,
                barThicknessDp = 8.dp,
                chartHeight = chartHeight,
            )

            if (tooltipState != null) {
                DataPointTooltip(
                    isVisible = true,
                    data = tooltipState,
                    onDismissRequest = { selectedState = null },
                )
            }
        }
    }
}

@Composable
fun SleepTrendChartLegends(
    barColor: Color,
    lineColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier =
                    Modifier
                        .size(width = 12.dp, height = 8.dp)
                        .background(color = barColor, shape = MaterialTheme.shapes.extraSmall),
            )
            Spacer(Modifier.width(MaterialTheme.spacing.extraSmallMedium))
            Text(
                text = stringResource(R.string.sleep_trend_legend_window),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier =
                    Modifier
                        .size(width = 16.dp, height = 2.dp)
                        .background(lineColor),
            )
            Spacer(Modifier.width(MaterialTheme.spacing.extraSmallMedium))
            Text(
                text = stringResource(R.string.sleep_trend_legend_duration),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun SleepTrendSkeleton(modifier: Modifier = Modifier) {
    SkeletonCard(
        height = 250.dp,
        modifier = modifier.fillMaxWidth(),
    )
}
