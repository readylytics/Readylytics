package app.readylytics.health.feature.vitals.bloodpressure

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.LocalExtendedColors
import app.readylytics.health.core.ui.common.ChartUtils
import app.readylytics.health.core.ui.common.DailyDataPoint
import app.readylytics.health.core.ui.components.ChartDefaults
import app.readylytics.health.core.ui.components.DataPointTooltip
import app.readylytics.health.core.ui.components.DataPointTooltipData
import app.readylytics.health.core.ui.components.EmptyChartPlaceholder
import app.readylytics.health.core.ui.components.InvisibleMarker
import app.readylytics.health.core.ui.components.VicoChartTooltipOverlay
import app.readylytics.health.core.ui.components.ZoneBandDecoration
import app.readylytics.health.core.ui.components.rememberChartMarkerVisibilityListener
import app.readylytics.health.core.ui.components.zoneBandColors
import app.readylytics.health.domain.model.diastolicZoneBands
import app.readylytics.health.domain.model.systolicZoneBands
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
    modifier: Modifier = Modifier,
    scrollState: VicoScrollState = rememberVicoScrollState(scrollEnabled = rangeDays > 7),
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
    onDaySelected: ((dayOffset: Int, canvasX: Float, canvasY: Float) -> Unit)? = null,
    externalSelectedDayOffset: Int? = null,
    externalSelectedCanvasX: Float? = null,
    showTooltip: Boolean = true,
    parentScrollInProgress: () -> Boolean = { false },
) {
    var tooltipState by remember { mutableStateOf<DataPointTooltipData?>(null) }
    var selectedPointOffset by remember { mutableStateOf<Offset?>(null) }

    LaunchedEffect(tooltipState) {
        if (tooltipState == null) selectedPointOffset = null
    }

    // Clear internal selection when the time range changes to avoid stale overlays
    LaunchedEffect(rangeDays) {
        tooltipState = null
        selectedPointOffset = null
    }

    // Clear tooltip when the chart is scrolled/panned (Vico horizontal scroll)
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.value }.collect {
            tooltipState = null
            selectedPointOffset = null
        }
    }

    // Clear tooltip when the parent list scrolls vertically.
    // Fires on both true (scroll started) and false (scroll ended) to
    // eliminate stale tooltip state that slips through mid-scroll recompositions.
    val currentParentScrollInProgress by rememberUpdatedState(parentScrollInProgress)
    LaunchedEffect(Unit) {
        snapshotFlow { currentParentScrollInProgress() }.collect {
            tooltipState = null
            selectedPointOffset = null
        }
    }

    if (points.none { it.value != null }) {
        EmptyChartPlaceholder(modifier = modifier)
        return
    }

    val (minY, maxY) =
        remember(points) {
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
    val bands =
        if (isDiastolic) {
            diastolicZoneBands()
        } else {
            app.readylytics.health.domain.model
                .systolicZoneBands()
        }
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

    LaunchedEffect(points) {
        modelProducer.runTransaction {
            val valid = points.filter { it.value != null }
            lineModel {
                series(
                    x = valid.map { it.dayOffset },
                    y = valid.mapNotNull { it.value?.toDouble() },
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

    val dotComponent = rememberShapeComponent(fill = Fill(lineColor), shape = CircleShape)
    val lineFill = remember(lineColor) { LineCartesianLayer.LineFill.single(Fill(lineColor)) }
    val areaFill =
        remember(lineColor) {
            LineCartesianLayer.AreaFill.single(
                Fill(
                    brush =
                        Brush.verticalGradient(
                            colors = listOf(lineColor.copy(alpha = 0.3f), lineColor.copy(alpha = 0.0f)),
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

    val markerVisibilityListener =
        rememberChartMarkerVisibilityListener { x, _, canvasX, canvasY ->
            val dayOffset = x.toInt()
            val date = ChartUtils.dayOffsetToLocalDate(dayOffset, rangeStartMs)
            val dateString = ChartUtils.formatTooltipDate(date)
            val value = points.firstOrNull { it.dayOffset == dayOffset }?.value
            val valueText = value?.let { "${it.roundToInt()} mmHg" } ?: "—"
            if (showTooltip) {
                // Standalone mode: track selection internally
                selectedPointOffset = Offset(canvasX, canvasY)
                tooltipState =
                    DataPointTooltipData(
                        valueText = valueText,
                        dateText = dateString,
                        offset =
                            androidx.compose.ui.unit
                                .IntOffset(canvasX.toInt(), canvasY.toInt()),
                    )
            }
            // Coordinated mode: only notify the parent; overlay is driven by externalCanvasX
            onDaySelected?.invoke(dayOffset, canvasX, canvasY)
        }

    val startAxisValueFormatter =
        remember {
            CartesianValueFormatter { _, value, _ ->
                // Pad to ensure 3 characters using figure space for equal width alignment
                value.roundToInt().toString().padStart(3, '\u2007')
            }
        }

    val lineProvider = remember(line) { LineCartesianLayer.LineProvider.series(line) }

    val baselineLineComponent = rememberLineComponent(fill = Fill(baselineColor), thickness = 1.dp)
    val decorations =
        remember(zoneBandDecoration, isDiastolic, baselineLineComponent) {
            listOf(
                zoneBandDecoration,
                HorizontalLine(
                    y = { if (isDiastolic) 80.0 else 120.0 },
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
                            itemPlacer = remember(rangeDays) { ChartDefaults.itemPlacerForRangeDays(rangeDays) },
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
            // In coordinated mode selectedPointOffset is always null;
            // the overlay is driven solely by externalCanvasX/externalDataY.
            selectedPointOffset = if (showTooltip) selectedPointOffset else null,
            modifier = Modifier.fillMaxWidth().height(180.dp),
            pulseColor = lineColor,
            externalCanvasX = externalSelectedCanvasX,
            externalDataY = points.firstOrNull { it.dayOffset == externalSelectedDayOffset }?.value?.toDouble(),
            minY = minY,
            maxY = maxY,
        )

        if (showTooltip && tooltipState != null) {
            DataPointTooltip(isVisible = true, data = tooltipState!!, onDismissRequest = { tooltipState = null })
        }
    }
}
