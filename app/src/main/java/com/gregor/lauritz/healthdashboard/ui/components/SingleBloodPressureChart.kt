package com.gregor.lauritz.healthdashboard.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.ui.common.DailyDataPoint
import com.gregor.lauritz.healthdashboard.domain.model.diastolicZoneBands
import com.gregor.lauritz.healthdashboard.domain.model.systolicZoneBands
import com.gregor.lauritz.healthdashboard.ui.components.ChartDefaults
import com.gregor.lauritz.healthdashboard.ui.common.ChartUtils
import com.gregor.lauritz.healthdashboard.ui.components.DataPointTooltip
import com.gregor.lauritz.healthdashboard.ui.components.DataPointTooltipData
import com.gregor.lauritz.healthdashboard.ui.components.EmptyChartPlaceholder
import com.gregor.lauritz.healthdashboard.ui.components.InvisibleMarker
import com.gregor.lauritz.healthdashboard.ui.components.VicoChartTooltipOverlay
import com.gregor.lauritz.healthdashboard.ui.components.ZoneBandDecoration
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.gregor.lauritz.healthdashboard.ui.components.zoneBandColors
import com.gregor.lauritz.healthdashboard.ui.theme.LocalExtendedColors
import androidx.compose.ui.unit.IntOffset
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
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import kotlin.math.roundToInt

/**
 * Renders a single‑series blood pressure chart (either systolic or diastolic).
 * The [isDiastolic] flag determines the colour palette and zone‑band definitions.
 */
@Composable
fun SingleBloodPressureChart(
    points: List<DailyDataPoint>,
    rangeStartMs: Long,
    rangeDays: Int,
    isDiastolic: Boolean,
    scrollState: VicoScrollState = rememberVicoScrollState(scrollEnabled = rangeDays > 7),
    zoomState: VicoZoomState = rememberVicoZoomState(
        zoomEnabled = rangeDays > 7,
        initialZoom = Zoom.Content,
        minZoom = Zoom.Content,
        maxZoom = remember(rangeDays) {
            when (rangeDays) {
                30 -> Zoom.fixed(6f)
                180 -> Zoom.fixed(25f)
                else -> Zoom.Content
            }
        },
    ),
    modifier: Modifier = Modifier,
) {
    var tooltipState by remember { mutableStateOf<DataPointTooltipData?>(null) }
    var selectedPointOffset by remember { mutableStateOf<Offset?>(null) }

    LaunchedEffect(tooltipState) {
        if (tooltipState == null) selectedPointOffset = null
    }

    if (points.none { it.value != null }) {
        EmptyChartPlaceholder(modifier = modifier)
        return
    }

    val (minY, maxY) = remember(points) {
        val vals = points.mapNotNull { it.value }
        if (vals.isEmpty()) return@remember 40.0 to 180.0
        val lo = vals.minOrNull() ?: 40f
        val hi = vals.maxOrNull() ?: 180f
        val scaledMin = (lo - 10f).coerceAtLeast(30f)
        val scaledMax = (hi + 10f).coerceAtMost(220f)
        scaledMin.toDouble() to scaledMax.toDouble()
    }

    val labelComponent = ChartDefaults.labelTextComponent()
    val axisLabelComponent = ChartDefaults.axisLabelTextComponent()
    val baselineColor = MaterialTheme.colorScheme.onSurfaceVariant
    val guidelineComponent = ChartDefaults.guidelineComponent()
    val extendedColors = LocalExtendedColors.current
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val errorContainer = MaterialTheme.colorScheme.errorContainer
    val lineColor = if (isDiastolic) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primary
    val bands = if (isDiastolic) diastolicZoneBands() else com.gregor.lauritz.healthdashboard.domain.model.systolicZoneBands()
    val colors = zoneBandColors(
        bands = bands,
        extendedColors = extendedColors,
        primaryContainer = primaryContainer,
        errorContainer = errorContainer,
        optimalAlpha = 0.45f,
    )
    val zoneBandDecoration = remember(bands, colors, minY, maxY) { ZoneBandDecoration(bands, colors, minY, maxY) }

    val modelProducer = remember { CartesianChartModelProducer() }
    val xAxisFormatter = ChartDefaults.rememberDayOffsetFormatter(rangeStartMs)

    LaunchedEffect(points) {
        modelProducer.runTransaction {
            val valid = points.filter { it.value != null }
            lineSeries {
                series(
                    x = valid.map { it.dayOffset },
                    y = valid.mapNotNull { it.value?.toDouble() },
                )
            }
        }
    }

    val rangeProvider = remember(minY, maxY, rangeDays) {
        CartesianLayerRangeProvider.fixed(
            minX = 0.0,
            maxX = (rangeDays - 1).toDouble(),
            minY = minY,
            maxY = maxY,
        )
    }

    val dotComponent = rememberShapeComponent(fill = Fill(lineColor), shape = CircleShape)
    val line = LineCartesianLayer.rememberLine(
        fill = LineCartesianLayer.LineFill.single(Fill(lineColor)),
        pointProvider = LineCartesianLayer.PointProvider.single(
            LineCartesianLayer.Point(dotComponent, 6.dp),
        ),
    )

    val markerVisibilityListener = rememberChartMarkerVisibilityListener { x, _, canvasX, canvasY ->
        val dayOffset = x.toInt()
        val date = ChartUtils.dayOffsetToLocalDate(dayOffset, rangeStartMs)
        val dateString = ChartUtils.formatTooltipDate(date)
        val value = points.firstOrNull { it.dayOffset == dayOffset }?.value
        val valueText = value?.let { "${it.roundToInt()} mmHg" } ?: "—"
        selectedPointOffset = Offset(canvasX, canvasY)
        tooltipState = DataPointTooltipData(
            valueText = valueText,
            dateText = dateString,
            offset = androidx.compose.ui.unit.IntOffset(canvasX.toInt(), canvasY.toInt()),
        )
    }

    Box(modifier = modifier.fillMaxWidth()) {
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberLineCartesianLayer(
                    lineProvider = LineCartesianLayer.LineProvider.series(line),
                    rangeProvider = rangeProvider,
                ),
                startAxis = VerticalAxis.rememberStart(
                    label = labelComponent,
                    valueFormatter = CartesianValueFormatter { _, value, _ -> value.roundToInt().toString() },
                    guideline = guidelineComponent,
                    title = { "mmHg" },
                    titleComponent = axisLabelComponent,
                ),
                bottomAxis = HorizontalAxis.rememberBottom(
                    label = labelComponent,
                    valueFormatter = xAxisFormatter,
                    itemPlacer = remember(rangeDays) { ChartDefaults.itemPlacerForRangeDays(rangeDays) },
                    guideline = guidelineComponent,
                ),
                decorations = listOfNotNull(
                    zoneBandDecoration,
                    HorizontalLine(
                        y = { if (isDiastolic) 80.0 else 120.0 },
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
        VicoChartTooltipOverlay(selectedPointOffset = selectedPointOffset, modifier = Modifier.fillMaxWidth().height(180.dp))
    }

    if (tooltipState != null) {
        DataPointTooltip(isVisible = true, data = tooltipState!!, onDismissRequest = { tooltipState = null })
    }
}
