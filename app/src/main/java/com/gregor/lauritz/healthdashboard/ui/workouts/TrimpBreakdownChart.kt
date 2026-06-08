package com.gregor.lauritz.healthdashboard.ui.workouts

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.R
import com.gregor.lauritz.healthdashboard.ui.components.ChartDefaults
import com.gregor.lauritz.healthdashboard.ui.components.DataPointTooltip
import com.gregor.lauritz.healthdashboard.ui.components.DataPointTooltipData
import com.gregor.lauritz.healthdashboard.ui.components.InvisibleMarker
import com.gregor.lauritz.healthdashboard.ui.components.VicoChartTooltipOverlay
import com.gregor.lauritz.healthdashboard.ui.components.rememberChartMarkerVisibilityListener
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.Fill
import kotlin.math.roundToInt

private const val TARGET_X_AXIS_LABELS = 6

@Composable
fun TrimpBreakdownChart(
    chartData: List<Pair<Double, Double>>,
    durationMinutes: Int,
    parentScrollInProgress: Boolean = false,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.heart_rate_title), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(16.dp))
            if (chartData.isEmpty()) {
                Text(stringResource(R.string.message_no_hr_data))
            } else {
                HrChart(chartData, durationMinutes, parentScrollInProgress)
            }
        }
    }
}

@Composable
private fun HrChart(
    chartData: List<Pair<Double, Double>>,
    durationMinutes: Int,
    parentScrollInProgress: Boolean = false,
) {
    var tooltipState by remember { mutableStateOf<DataPointTooltipData?>(null) }
    var selectedPointOffset by remember { mutableStateOf<Offset?>(null) }

    LaunchedEffect(tooltipState) {
        if (tooltipState == null) {
            selectedPointOffset = null
        }
    }

    // Clear tooltip when the parent list scrolls vertically
    LaunchedEffect(parentScrollInProgress) {
        if (parentScrollInProgress) {
            tooltipState = null
            selectedPointOffset = null
        }
    }

    val modelProducer = remember { CartesianChartModelProducer() }

    LaunchedEffect(chartData) {
        if (chartData.isEmpty()) return@LaunchedEffect
        modelProducer.runTransaction {
            lineModel {
                series(
                    x = chartData.map { it.first },
                    y = chartData.map { it.second },
                )
            }
        }
    }

    val labelMinutes =
        remember(durationMinutes) {
            computeLabelMinutes(durationMinutes, TARGET_X_AXIS_LABELS)
        }

    val itemPlacer =
        remember(labelMinutes) {
            val base =
                HorizontalAxis.ItemPlacer.aligned(
                    spacing = { 1 },
                    addExtremeLabelPadding = true,
                )
            object : HorizontalAxis.ItemPlacer by base {
                override fun getLabelValues(
                    context: CartesianDrawingContext,
                    visibleXRange: ClosedFloatingPointRange<Double>,
                    fullXRange: ClosedFloatingPointRange<Double>,
                    maxLabelWidth: Float,
                ): List<Double> = labelMinutes.filter { it in fullXRange }
            }
        }

    val rangeProvider =
        remember(chartData) {
            val values = chartData.map { it.second }
            val lo = values.minOrNull() ?: 0.0
            val hi = values.maxOrNull() ?: 0.0
            CartesianLayerRangeProvider.fixed(
                minY = (lo - 5.0).coerceAtLeast(0.0),
                maxY = hi + 5.0,
            )
        }

    val minutesLabel = stringResource(R.string.label_minutes)
    val labelComponent = ChartDefaults.labelTextComponent()
    val axisLabelComponent = ChartDefaults.axisLabelTextComponent()
    val guidelineComponent = ChartDefaults.guidelineComponent()

    val markerVisibilityListener =
        rememberChartMarkerVisibilityListener(
            onPointSelected = { x, y, canvasX, canvasY ->
                val minute = x.toInt().coerceIn(0, durationMinutes - 1)
                val valueText = "${y.toInt()} bpm"
                val dateText = "$minute min"
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

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var isMultiTouch = false
                        while (!isMultiTouch) {
                            val event = awaitPointerEvent()
                            // Stop polling once all fingers are lifted
                            if (event.changes.none { it.pressed }) break
                            if (event.changes.size > 1) {
                                // Multi-touch: pan/zoom detected — clear tooltip
                                isMultiTouch = true
                                tooltipState = null
                                selectedPointOffset = null
                            }
                        }
                    }
                },
    ) {
        CartesianChartHost(
            chart =
                rememberCartesianChart(
                    rememberLineCartesianLayer(
                        lineProvider =
                            LineCartesianLayer.LineProvider.series(
                                LineCartesianLayer.rememberLine(
                                    fill = LineCartesianLayer.LineFill.single(Fill(MaterialTheme.colorScheme.primary)),
                                ),
                            ),
                        rangeProvider = rangeProvider,
                    ),
                    startAxis =
                        VerticalAxis.rememberStart(
                            label = labelComponent,
                            title = { "bpm" },
                            titleComponent = axisLabelComponent,
                            guideline = guidelineComponent,
                            valueFormatter = CartesianValueFormatter { _, value, _ -> value.roundToInt().toString() },
                        ),
                    bottomAxis =
                        HorizontalAxis.rememberBottom(
                            label = labelComponent,
                            title = { minutesLabel },
                            titleComponent = axisLabelComponent,
                            guideline = guidelineComponent,
                            valueFormatter = CartesianValueFormatter { _, value, _ -> value.roundToInt().toString() },
                            itemPlacer = itemPlacer,
                        ),
                    marker = InvisibleMarker,
                    markerVisibilityListener = markerVisibilityListener,
                ),
            modelProducer = modelProducer,
            zoomState =
                rememberVicoZoomState(
                    zoomEnabled = false,
                    initialZoom = Zoom.Content,
                ),
            modifier = Modifier.fillMaxWidth().height(200.dp),
        )

        VicoChartTooltipOverlay(
            selectedPointOffset = selectedPointOffset,
            modifier = Modifier.fillMaxWidth().height(200.dp),
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

private fun computeLabelMinutes(
    durationMinutes: Int,
    target: Int,
): List<Double> {
    if (durationMinutes <= 0) return listOf(0.0)
    val intervals = (target - 1).coerceAtLeast(1)
    if (durationMinutes <= intervals) {
        return (0..durationMinutes).map { it.toDouble() }
    }
    val step = durationMinutes.toDouble() / intervals
    return (0..intervals)
        .map { (it * step).roundToInt().toDouble() }
        .distinct()
}
