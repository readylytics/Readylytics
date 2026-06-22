package app.readylytics.health.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.readylytics.health.R
import app.readylytics.health.ui.common.ChartUtils
import app.readylytics.health.ui.common.DailyDataPoint
import app.readylytics.health.ui.theme.LocalExtendedColors
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
import kotlin.math.roundToInt

@Composable
fun BloodPressureTrendChart(
    systolicPoints: List<DailyDataPoint>,
    diastolicPoints: List<DailyDataPoint>,
    rangeStartMs: Long,
    rangeDays: Int,
    modifier: Modifier = Modifier,
    scrollState: VicoScrollState = rememberVicoScrollState(scrollEnabled = rangeDays > 7),
    // Same fix as TrendChart: Zoom.min(Zoom.Content, Zoom.fixed(1f)) as minZoom floor.
    // See TrendChart's zoomState comment for the full rationale.
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
                        else -> Zoom.fixed(2f)
                    }
                },
        ),
    parentScrollInProgress: () -> Boolean = { false },
) {
    var tooltipState by remember { mutableStateOf<DataPointTooltipData?>(null) }
    var selectedPointOffset by remember { mutableStateOf<Offset?>(null) }

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
    // Fire on both transitions to eliminate stale tooltip state at scroll-end.
    LaunchedEffect(Unit) {
        snapshotFlow { parentScrollInProgress() }.collect {
            tooltipState = null
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
    val baselineColor = MaterialTheme.colorScheme.onSurfaceVariant
    val guidelineComponent = ChartDefaults.guidelineComponent()
    val extendedColors = LocalExtendedColors.current
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val errorContainer = MaterialTheme.colorScheme.errorContainer
    val systolicColor = MaterialTheme.colorScheme.primary
    val diastolicColor = MaterialTheme.colorScheme.tertiaryContainer
    val bands =
        app.readylytics.health.domain.model
            .systolicZoneBands()
    // Define colors and containers for blood pressure chart

    // Use generic utility for colors; increase optimal opacity for better distinction
    val colors =
        zoneBandColors(
            bands = bands,
            extendedColors = extendedColors,
            primaryContainer = primaryContainer,
            errorContainer = errorContainer,
            optimalAlpha = 0.45f,
        )
    val zoneBandDecoration = remember(bands, colors, minY, maxY) { ZoneBandDecoration(bands, colors, minY, maxY) }

    val modelProducer = remember { CartesianChartModelProducer() }

    val xAxisFormatter = ChartDefaults.rememberDayOffsetFormatter(rangeStartMs)

    LaunchedEffect(systolicPoints, diastolicPoints) {
        modelProducer.runTransaction {
            val validSystolic = systolicPoints.filter { it.value != null }
            val validDiastolic = diastolicPoints.filter { it.value != null }
            lineModel {
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
    val systolicLineFill = remember(systolicColor) { LineCartesianLayer.LineFill.single(Fill(systolicColor)) }
    val systolicAreaFill =
        remember(systolicColor) {
            LineCartesianLayer.AreaFill.single(
                Fill(
                    brush =
                        Brush.verticalGradient(
                            colors = listOf(systolicColor.copy(alpha = 0.3f), systolicColor.copy(alpha = 0.0f)),
                        ),
                ),
            )
        }
    val systolicLine =
        LineCartesianLayer.rememberLine(
            fill = systolicLineFill,
            areaFill = systolicAreaFill,
            pointProvider =
                LineCartesianLayer.PointProvider.single(
                    LineCartesianLayer.Point(systolicDotComponent, 6.dp),
                ),
            interpolator = LineCartesianLayer.Interpolator.cubic(0.2f),
        )

    val diastolicDotComponent = rememberShapeComponent(fill = Fill(diastolicColor), shape = CircleShape)
    val diastolicLineFill = remember(diastolicColor) { LineCartesianLayer.LineFill.single(Fill(diastolicColor)) }
    val diastolicAreaFill =
        remember(diastolicColor) {
            LineCartesianLayer.AreaFill.single(
                Fill(
                    brush =
                        Brush.verticalGradient(
                            colors = listOf(diastolicColor.copy(alpha = 0.2f), diastolicColor.copy(alpha = 0.0f)),
                        ),
                ),
            )
        }
    val diastolicLine =
        LineCartesianLayer.rememberLine(
            fill = diastolicLineFill,
            areaFill = diastolicAreaFill,
            pointProvider =
                LineCartesianLayer.PointProvider.single(
                    LineCartesianLayer.Point(diastolicDotComponent, 6.dp),
                ),
            interpolator = LineCartesianLayer.Interpolator.cubic(0.2f),
        )

    val lineProvider =
        remember(systolicLine, diastolicLine) {
            LineCartesianLayer.LineProvider.series(systolicLine, diastolicLine)
        }

    val markerVisibilityListener =
        rememberChartMarkerVisibilityListener { x, _, canvasX, canvasY ->
            val dayOffset = x.toInt()
            val date = ChartUtils.dayOffsetToLocalDate(dayOffset, rangeStartMs)
            val dateText = ChartUtils.formatTooltipDate(date)
            val sysNearest = systolicPoints.firstOrNull { it.dayOffset == dayOffset }
            val diaNearest = diastolicPoints.firstOrNull { it.dayOffset == dayOffset }
            val valueText =
                if (sysNearest?.value != null && diaNearest?.value != null) {
                    "${sysNearest.value.toInt()}/${diaNearest.value.toInt()} mmHg"
                } else if (sysNearest?.value != null) {
                    "Sys: ${sysNearest.value.toInt()} mmHg"
                } else {
                    "—"
                }
            selectedPointOffset = Offset(canvasX, canvasY)
            tooltipState =
                DataPointTooltipData(
                    valueText = valueText,
                    dateText = dateText,
                    offset =
                        androidx.compose.ui.unit
                            .IntOffset(canvasX.toInt(), canvasY.toInt()),
                )
        }

    val startAxisValueFormatter =
        remember {
            CartesianValueFormatter { _, value, _ ->
                value.roundToInt().toString()
            }
        }

    val baselineLineComponent = rememberLineComponent(fill = Fill(baselineColor), thickness = 1.dp)
    val decorations =
        remember(zoneBandDecoration, baselineLineComponent) {
            listOf(
                zoneBandDecoration,
                HorizontalLine(
                    y = { 120.0 },
                    line = baselineLineComponent,
                ),
                HorizontalLine(
                    y = { 80.0 },
                    line = baselineLineComponent,
                ),
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
            pulseColor = systolicColor,
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
            text = stringResource(R.string.label_systolic_ref),
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
            text = stringResource(R.string.label_diastolic_ref),
            style = MaterialTheme.typography.labelSmall,
            color = diastolicColor,
        )
    }
}
