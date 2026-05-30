package com.gregor.lauritz.healthdashboard.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.domain.model.ZoneBand
import com.gregor.lauritz.healthdashboard.domain.model.systolicZoneBands
import com.gregor.lauritz.healthdashboard.ui.common.ChartUtils
import com.gregor.lauritz.healthdashboard.ui.common.DailyDataPoint
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.VicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.VicoZoomState
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.lineSeries
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
import kotlin.math.roundToInt

@Composable
fun TrendCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(16.dp))
            content()
        }
    }
}

@Composable
fun TrendCard(
    title: String,
    value: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    TrendCard(title = title, modifier = modifier) {
        Column {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun TrendChart(
    points: List<DailyDataPoint>,
    rangeStartMs: Long,
    rangeDays: Int,
    metricName: String,
    baselineUnit: String,
    baseline: Float? = null,
    baselineLabel: String = "Baseline",
    baselineDecimalPlaces: Int = 0,
    axisDecimalPlaces: Int = 0,
    showBaseline: Boolean = true,
    scrollState: VicoScrollState = rememberVicoScrollState(scrollEnabled = rangeDays > 7),
    zoomState: VicoZoomState =
        rememberVicoZoomState(
            zoomEnabled = rangeDays > 7,
            initialZoom = Zoom.Content,
            minZoom = Zoom.Content,
            maxZoom =
                remember(rangeDays) {
                    when (rangeDays) {
                        30 -> Zoom.fixed(6f)
                        180 -> Zoom.fixed(25f)
                        else -> Zoom.Content
                    }
                },
        ),
    zoneBands: List<ZoneBand>? = null,
    modifier: Modifier = Modifier,
) {
    var tooltipState by remember { mutableStateOf<DataPointTooltipData?>(null) }
    var selectedPointOffset by remember { mutableStateOf<Offset?>(null) }

    // Clear highlight when tooltip is hidden
    LaunchedEffect(tooltipState) {
        if (tooltipState == null) {
            selectedPointOffset = null
        }
    }

    if (points.none { it.value != null }) {
        EmptyChartPlaceholder(modifier = modifier)
        return
    }

    val calculatedBaseline =
        remember(points) {
            val values = points.mapNotNull { it.value }.sorted()
            if (values.isEmpty()) return@remember 0f
            val mid = values.size / 2
            if (values.size % 2 == 0) (values[mid - 1] + values[mid]) / 2f else values[mid]
        }

    // Use provided baseline if available, otherwise fall back to calculated baseline
    val baselineValue = baseline ?: calculatedBaseline

    val (minY, maxY) =
        remember(points) {
            val values = points.mapNotNull { it.value }
            if (values.isEmpty()) return@remember 0.0 to 0.0
            val lo = values.minOrNull() ?: 0f
            val hi = values.maxOrNull() ?: 0f
            val scaledMin = lo * 0.9f
            val scaledMax = hi * 1.1f
            kotlin.math.floor(scaledMin).toDouble() to kotlin.math.ceil(scaledMax).toDouble()
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

    val xAxisFormatter = ChartDefaults.rememberDayOffsetFormatter(rangeStartMs)

    LaunchedEffect(points) {
        modelProducer.runTransaction {
            val validPoints = points.filter { it.value != null }
            lineSeries {
                series(
                    x = validPoints.map { it.dayOffset },
                    y = validPoints.mapNotNull { it.value?.toDouble() },
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
    val line =
        LineCartesianLayer.rememberLine(
            fill = LineCartesianLayer.LineFill.single(Fill(dotColor)),
            pointProvider =
                LineCartesianLayer.PointProvider.single(
                    LineCartesianLayer.Point(dotComponent, 6.dp),
                ),
        )

    val markerVisibilityListener =
        rememberChartMarkerVisibilityListener(
            onPointSelected = { x, y, canvasX, canvasY ->
                val dayOffset = x.toInt()
                val value = y.toFloat()
                val date = ChartUtils.dayOffsetToLocalDate(dayOffset, rangeStartMs)
                val dateString = ChartUtils.formatTooltipDate(date)
                val valueText =
                    if (baselineUnit.equals("steps", ignoreCase = true)) {
                        "${value.toInt()}"
                    } else {
                        "${value.toInt()} $baselineUnit"
                    }
                val dateText = dateString
                selectedPointOffset = Offset(canvasX, canvasY)
                tooltipState =
                    DataPointTooltipData(
                        valueText = valueText,
                        dateText = dateText,
                        offset =
                            androidx.compose.ui.unit.IntOffset(
                                canvasX.toInt(),
                                canvasY.toInt(),
                            ),
                    )
            },
        )

    Box(modifier = modifier.fillMaxWidth()) {
        if (zoneBands != null) {
            ZoneBandOverlay(
                zoneBands = zoneBands,
                minY = minY,
                maxY = maxY,
                modifier = Modifier.fillMaxWidth().height(180.dp),
            )
        }
        CartesianChartHost(
            chart =
                rememberCartesianChart(
                    rememberLineCartesianLayer(
                        lineProvider = LineCartesianLayer.LineProvider.series(line),
                        rangeProvider = rangeProvider,
                    ),
                    startAxis =
                        VerticalAxis.rememberStart(
                            label = labelComponent,
                            valueFormatter =
                                CartesianValueFormatter { _, value, _ ->
                                    if (axisDecimalPlaces == 0) {
                                        value.roundToInt().toString()
                                    } else {
                                        String.format("%.${axisDecimalPlaces}f", value)
                                    }
                                },
                            guideline = guidelineComponent,
                            title = { baselineUnit },
                            titleComponent = axisLabelComponent,
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
                    decorations =
                        if (shouldShowBaseline) {
                            listOf(
                                HorizontalLine(
                                    y = { baselineValue.toDouble() },
                                    line = rememberLineComponent(fill = Fill(baselineColor), thickness = 1.dp),
                                ),
                            )
                        } else {
                            emptyList()
                        },
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
            modifier = Modifier.fillMaxWidth().height(180.dp),
        )
    }

    if (tooltipState != null) {
        DataPointTooltip(
            isVisible = true,
            data = tooltipState!!,
            onDismissRequest = { tooltipState = null },
        )
    }

    if (shouldShowBaseline) {
        Spacer(Modifier.height(6.dp))
        BaselineLegend(
            value = baselineValue,
            unit = baselineUnit,
            label = baselineLabel,
            color = baselineColor,
            decimalPlaces = baselineDecimalPlaces,
        )
    }
}

@Composable
fun BaselineLegend(
    value: Float,
    unit: String,
    label: String = "Baseline",
    color: Color,
    decimalPlaces: Int = 0,
    modifier: Modifier = Modifier,
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
        Spacer(Modifier.width(8.dp))
        val formattedValue =
            if (decimalPlaces == 0) {
                value.roundToInt().toString()
            } else {
                String.format("%.${decimalPlaces}f", value)
            }
        Text(
            text = "$label: $formattedValue $unit",
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

@Composable
fun BloodPressureTrendChart(
    systolicPoints: List<DailyDataPoint>,
    diastolicPoints: List<DailyDataPoint>,
    rangeStartMs: Long,
    rangeDays: Int,
    scrollState: VicoScrollState = rememberVicoScrollState(scrollEnabled = rangeDays > 7),
    zoomState: VicoZoomState =
        rememberVicoZoomState(
            zoomEnabled = rangeDays > 7,
            initialZoom = Zoom.Content,
            minZoom = Zoom.Content,
            maxZoom =
                remember(rangeDays) {
                    when (rangeDays) {
                        30 -> Zoom.fixed(6f)
                        180 -> Zoom.fixed(25f)
                        else -> Zoom.Content
                    }
                },
        ),
    showZoneBands: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var tooltipState by remember { mutableStateOf<DataPointTooltipData?>(null) }
    var selectedPointOffset by remember { mutableStateOf<Offset?>(null) }

    LaunchedEffect(tooltipState) {
        if (tooltipState == null) {
            selectedPointOffset = null
        }
    }

    if (systolicPoints.none { it.value != null } || diastolicPoints.none { it.value != null }) {
        EmptyChartPlaceholder(modifier = modifier)
        return
    }

    val (minY, maxY) =
        remember(systolicPoints, diastolicPoints) {
            val sysVals = systolicPoints.mapNotNull { it.value }
            val diaVals = diastolicPoints.mapNotNull { it.value }
            val allVals = sysVals + diaVals
            if (allVals.isEmpty()) return@remember 40.0 to 180.0
            val lo = allVals.minOrNull() ?: 40f
            val hi = allVals.maxOrNull() ?: 180f
            val scaledMin = (lo - 10f).coerceAtLeast(30f)
            val scaledMax = (hi + 10f).coerceAtMost(220f)
            scaledMin.toDouble() to scaledMax.toDouble()
        }

    val labelComponent = ChartDefaults.labelTextComponent()
    val axisLabelComponent = ChartDefaults.axisLabelTextComponent()
    val baselineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    val guidelineComponent = ChartDefaults.guidelineComponent()
    val systolicColor = MaterialTheme.colorScheme.primary
    val diastolicColor = MaterialTheme.colorScheme.tertiary

    val modelProducer = remember { CartesianChartModelProducer() }

    val xAxisFormatter = ChartDefaults.rememberDayOffsetFormatter(rangeStartMs)

    LaunchedEffect(systolicPoints, diastolicPoints) {
        modelProducer.runTransaction {
            val validSystolic = systolicPoints.filter { it.value != null }
            val validDiastolic = diastolicPoints.filter { it.value != null }
            lineSeries {
                series(
                    x = validSystolic.map { it.dayOffset },
                    y = validSystolic.mapNotNull { it.value?.toDouble() },
                )
                series(
                    x = validDiastolic.map { it.dayOffset },
                    y = validDiastolic.mapNotNull { it.value?.toDouble() },
                )
            }
        }
    }

    val rangeProvider =
        remember(minY, maxY, rangeDays) {
            CartesianLayerRangeProvider.fixed(
                minX = 0.0,
                maxX = (rangeDays - 1).toDouble(),
                minY = minY,
                maxY = maxY,
            )
        }

    val systolicDotComponent = rememberShapeComponent(fill = Fill(systolicColor), shape = CircleShape)
    val systolicLine =
        LineCartesianLayer.rememberLine(
            fill = LineCartesianLayer.LineFill.single(Fill(systolicColor)),
            pointProvider =
                LineCartesianLayer.PointProvider.single(
                    LineCartesianLayer.Point(systolicDotComponent, 6.dp),
                ),
        )

    val diastolicDotComponent = rememberShapeComponent(fill = Fill(diastolicColor), shape = CircleShape)
    val diastolicLine =
        LineCartesianLayer.rememberLine(
            fill = LineCartesianLayer.LineFill.single(Fill(diastolicColor)),
            pointProvider =
                LineCartesianLayer.PointProvider.single(
                    LineCartesianLayer.Point(diastolicDotComponent, 6.dp),
                ),
        )

    val lineProvider =
        remember(systolicLine, diastolicLine) {
            LineCartesianLayer.LineProvider.series(systolicLine, diastolicLine)
        }

    val markerVisibilityListener =
        rememberChartMarkerVisibilityListener(
            onPointSelected = { x, y, canvasX, canvasY ->
                val dayOffset = x.toInt()
                val date = ChartUtils.dayOffsetToLocalDate(dayOffset, rangeStartMs)
                val dateString = ChartUtils.formatTooltipDate(date)

                val sysPoint = systolicPoints.firstOrNull { it.dayOffset == dayOffset }?.value
                val diaPoint = diastolicPoints.firstOrNull { it.dayOffset == dayOffset }?.value

                val valueText =
                    if (sysPoint != null && diaPoint != null) {
                        "${sysPoint.roundToInt()}/${diaPoint.roundToInt()} mmHg"
                    } else if (sysPoint != null) {
                        "Sys: ${sysPoint.roundToInt()} mmHg"
                    } else if (diaPoint != null) {
                        "Dia: ${diaPoint.roundToInt()} mmHg"
                    } else {
                        "—"
                    }

                selectedPointOffset = Offset(canvasX, canvasY)
                tooltipState =
                    DataPointTooltipData(
                        valueText = valueText,
                        dateText = dateString,
                        offset =
                            androidx.compose.ui.unit.IntOffset(
                                canvasX.toInt(),
                                canvasY.toInt(),
                            ),
                    )
            },
        )

    Box(modifier = modifier.fillMaxWidth()) {
        if (showZoneBands) {
            ZoneBandOverlay(
                zoneBands = systolicZoneBands(),
                minY = minY,
                maxY = maxY,
                modifier = Modifier.fillMaxWidth().height(180.dp),
            )
        }
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
                            valueFormatter =
                                CartesianValueFormatter { _, value, _ ->
                                    value.roundToInt().toString()
                                },
                            guideline = guidelineComponent,
                            title = { "mmHg" },
                            titleComponent = axisLabelComponent,
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
                    decorations =
                        listOf(
                            HorizontalLine(
                                y = { 120.0 },
                                line = rememberLineComponent(fill = Fill(baselineColor), thickness = 1.dp),
                            ),
                            HorizontalLine(
                                y = { 80.0 },
                                line = rememberLineComponent(fill = Fill(baselineColor), thickness = 1.dp),
                            ),
                        ),
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
            modifier = Modifier.fillMaxWidth().height(180.dp),
        )
    }

    if (tooltipState != null) {
        DataPointTooltip(
            isVisible = true,
            data = tooltipState!!,
            onDismissRequest = { tooltipState = null },
        )
    }

    Spacer(Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(width = 12.dp, height = 2.dp)
                    .background(systolicColor),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Systolic (Ref: <120)",
            style = MaterialTheme.typography.labelSmall,
            color = systolicColor,
        )

        Spacer(Modifier.width(24.dp))

        Box(
            modifier =
                Modifier
                    .size(width = 12.dp, height = 2.dp)
                    .background(diastolicColor),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Diastolic (Ref: <80)",
            style = MaterialTheme.typography.labelSmall,
            color = diastolicColor,
        )
    }
}

@Composable
fun EmptyChartPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(180.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "No data available",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
