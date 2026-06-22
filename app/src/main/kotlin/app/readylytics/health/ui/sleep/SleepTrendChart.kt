package app.readylytics.health.ui.sleep

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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.readylytics.health.R
import app.readylytics.health.ui.common.ChartUtils
import app.readylytics.health.ui.common.DailyDataPoint
import app.readylytics.health.ui.common.DateFormatUtils
import app.readylytics.health.ui.common.SkeletonCard
import app.readylytics.health.ui.common.TimeRange
import app.readylytics.health.ui.components.ChartDefaults
import app.readylytics.health.ui.components.DataPointTooltip
import app.readylytics.health.ui.components.DataPointTooltipData
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
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

@Composable
fun SleepTrendCard(
    selectedRange: TimeRange,
    startOffsetPoints: List<DailyDataPoint>,
    durationSpanPoints: List<DailyDataPoint>,
    actualDurationPoints: List<DailyDataPoint>,
    rangeStartMs: Long,
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
                text = stringResource(R.string.sleep_trend_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(16.dp))

            SleepTrendChart(
                selectedRange = selectedRange,
                startOffsetPoints = startOffsetPoints,
                durationSpanPoints = durationSpanPoints,
                actualDurationPoints = actualDurationPoints,
                rangeStartMs = rangeStartMs,
                scrollState = scrollState,
                zoomState = zoomState,
                parentScrollInProgress = parentScrollInProgress,
            )
        }
    }
}

@Composable
fun SleepTrendChart(
    selectedRange: TimeRange,
    startOffsetPoints: List<DailyDataPoint>,
    durationSpanPoints: List<DailyDataPoint>,
    actualDurationPoints: List<DailyDataPoint>,
    rangeStartMs: Long,
    scrollState: VicoScrollState,
    zoomState: VicoZoomState,
    modifier: Modifier = Modifier,
    parentScrollInProgress: () -> Boolean = { false },
) {
    val rangeDays = selectedRange.days
    var selectedState by remember(startOffsetPoints, durationSpanPoints, actualDurationPoints, rangeStartMs) {
        mutableStateOf<SleepTrendSelectedState?>(null)
    }

    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.value }.collect {
            selectedState = null
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { parentScrollInProgress() }.collect { inProgress ->
            if (inProgress) selectedState = null
        }
    }

    val durationFormat = stringResource(R.string.sleep_trend_tooltip_duration_format)
    val bedtimeFormat = stringResource(R.string.sleep_trend_tooltip_bedtime_format)
    val hoursOnlyFormat = stringResource(R.string.sleep_duration_hours_only)

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

    val tooltipState =
        remember(selectedState, rangeStartMs, durationFormat, bedtimeFormat) {
            selectedState?.let { s ->
                val date = ChartUtils.dayOffsetToLocalDate(s.dayOffset, rangeStartMs)
                val dateText = ChartUtils.formatTooltipDate(date)

                fun formatTime(offsetHours: Float?): String {
                    if (offsetHours == null) return "—"
                    val totalMinutes = (offsetHours * 60f).roundToInt()
                    val positiveMinutes = (720 + totalMinutes).mod(1440)
                    val hour = positiveMinutes / 60
                    val minute = positiveMinutes % 60
                    return String.format(
                        Locale.getDefault(),
                        "%d:%02d %s",
                        if (hour > 12) {
                            hour - 12
                        } else if (hour == 0) {
                            12
                        } else {
                            hour
                        },
                        minute,
                        if (hour >= 12) "PM" else "AM",
                    )
                }

                val bedtimeText = formatTime(s.startOffsetValue)
                val wakeupText =
                    formatTime(
                        s.startOffsetValue?.let { start ->
                            s.durationSpanValue?.let { span ->
                                start + span
                            }
                        },
                    )

                val durationStr =
                    DateFormatUtils.formatSleepDuration(
                        ((s.actualDurationValue ?: 0f) * 60f).roundToInt(),
                    )
                val valueText =
                    String.format(
                        Locale.getDefault(),
                        durationFormat,
                        durationStr,
                    )
                val dateTextVal =
                    String.format(
                        Locale.getDefault(),
                        bedtimeFormat,
                        bedtimeText,
                        wakeupText,
                    )

                DataPointTooltipData(
                    valueText = valueText,
                    dateText = dateTextVal,
                    extraLine = dateText,
                    offset =
                        IntOffset(
                            s.canvasX.toInt(),
                            (s.lineCanvasY ?: s.barCanvasYTop ?: 0f).toInt(),
                        ),
                )
            }
        }

    val barColor = MaterialTheme.colorScheme.primary
    val lineColor = MaterialTheme.colorScheme.tertiary
    val labelComponent = ChartDefaults.labelTextComponent()
    val axisLabelComponent = ChartDefaults.axisLabelTextComponent()
    val guidelineComponent = ChartDefaults.guidelineComponent()

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
        remember(startOffsetPoints, durationSpanPoints, rangeDays) {
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

                override fun getMinY(
                    minY: Double,
                    maxY: Double,
                    extraStore: ExtraStore,
                ): Double {
                    val startVals = startOffsetPoints.mapNotNull { it.value }
                    val minVal = startVals.minOrNull() ?: 8.0f // default to 8:00 PM (8 hours since Noon)
                    return (floor(minVal.toDouble() - 1.0)).coerceAtLeast(0.0)
                }

                override fun getMaxY(
                    minY: Double,
                    maxY: Double,
                    extraStore: ExtraStore,
                ): Double {
                    val endVals =
                        startOffsetPoints
                            .zip(durationSpanPoints) { start, span ->
                                if (start.value != null && span.value != null) start.value + span.value else null
                            }.filterNotNull()
                    val maxVal = endVals.maxOrNull() ?: 20.0f // default to 8:00 AM next day (20 hours since Noon)
                    return (ceil(maxVal.toDouble() + 1.0)).coerceAtMost(24.0)
                }
            }
        }

    val rightRangeProvider =
        remember(actualDurationPoints, rangeDays) {
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
                    val vals = actualDurationPoints.mapNotNull { it.value?.toDouble() }
                    val maxVal = vals.maxOrNull() ?: 8.0
                    return (ceil(maxVal / 2.0) * 2.0).coerceAtLeast(10.0)
                }
            }
        }

    val xAxisFormatter = ChartDefaults.rememberDayOffsetFormatter(rangeStartMs)

    val hasData =
        remember(startOffsetPoints, durationSpanPoints, actualDurationPoints) {
            startOffsetPoints.any { it.value != null } ||
                durationSpanPoints.any { it.value != null } ||
                actualDurationPoints.any { it.value != null }
        }

    LaunchedEffect(startOffsetPoints, durationSpanPoints, actualDurationPoints) {
        modelProducer.runTransaction {
            val validStart = startOffsetPoints.filter { it.value != null }
            val validSpan = durationSpanPoints.filter { it.value != null }
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
            val validActual = actualDurationPoints.filter { it.value != null }
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
                        fill = Fill(barColor),
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
                        pointProvider =
                            LineCartesianLayer.PointProvider.single(
                                LineCartesianLayer.Point(
                                    rememberShapeComponent(fill = Fill(lineColor), shape = CircleShape),
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
            startOffsetPoints = startOffsetPoints,
            durationSpanPoints = durationSpanPoints,
            actualDurationPoints = actualDurationPoints,
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
                text = stringResource(R.string.message_no_data_available),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        Box(modifier = modifier.fillMaxWidth()) {
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
                                itemPlacer = remember(rangeDays) { ChartDefaults.itemPlacerForRangeDays(rangeDays) },
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
                barColor = barColor,
                lineColor = lineColor,
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

        Spacer(Modifier.height(16.dp))

        SleepTrendChartLegends(
            barColor = barColor,
            lineColor = lineColor,
        )
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
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier =
                    Modifier
                        .size(width = 12.dp, height = 8.dp)
                        .background(color = barColor, shape = MaterialTheme.shapes.extraSmall),
            )
            Spacer(Modifier.width(6.dp))
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
            Spacer(Modifier.width(6.dp))
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
